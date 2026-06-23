(ns tunarr.scheduler.scheduling.intent
  "Intent processing for natural-language schedule modifications.

   Translates high-level instructions like:
     • 'Replace the 6pm Tuesday block with Cheers'
     • 'Make the Enigma channel more detective-themed next week'
   into structured schedule operations and applies them via Pseudovision."

  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.scheduling.pseudovision :as pv-schedule]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Prompt construction
;; ---------------------------------------------------------------------------

(defn- slot->description
  "Convert a PV slot into a human-readable description for the LLM prompt.

   NOTE: PV slot field names are a best-guess kebab-case mapping (consistent
   with slot-spec->pseudovision-slot and the 'kebab-case everywhere' refactor).
   Re-verify against a live GET /api/schedules/:id/slots payload — see TODO in
   execute-operation!."
  [slot idx]
  (let [anchor (:anchor slot)
        time (when (= "fixed" anchor)
               (:start-time slot))
        dow (:days-of-week slot 127)
        days (cond
               (= dow 127) "every day"
               (= dow 31) "weekdays"
               (= dow 96) "weekends"
               :else (str "days bitmask " dow))
        fill (:fill-mode slot)
        tags (:required-tags slot)
        excluded (:excluded-tags slot)
        order (:playback-order slot)]
    (str "Block " idx ": "
         (when time (str "at " time " "))
         "(" days ") — "
         "fill mode: " fill
         (when (seq tags) (str ", tags: " (str/join ", " tags)))
         (when (seq excluded) (str ", exclude: " (str/join ", " excluded)))
         ", order: " order)))

(defn- tag-samples->description
  "Convert tag samples into a prompt-friendly description."
  [samples]
  (when (seq samples)
    (str/join "\n"
              (map (fn [{:keys [tag usage_count example_titles]}]
                     (str "  " tag
                          " (" usage_count " items)"
                          (when (seq example_titles)
                            (str " — e.g. " (str/join ", " (take 3 example_titles))))))
                   samples))))

(defn- build-context-prompt
  "Build the system/user prompt describing the current schedule state.

   Args:
     channel-name   — String name of the channel
     schedule       — PV schedule map with slots
     available-tags — Vector of tag strings (optional)
     tag-samples    — Vector of {:tag :usage_count :example_titles} (optional)"
  [channel-name schedule available-tags tag-samples]
  (let [slots (:slots schedule)
        slot-descriptions (map-indexed slot->description slots)]
    (str/join "\n"
              [(str "You are a TV scheduling assistant. You modify channel schedules for the '"
                    channel-name "' channel.")
               ""
               "CURRENT SCHEDULE:"
               (str/join "\n" slot-descriptions)
               ""
               (when (seq available-tags)
                 (str "AVAILABLE TAGS:\n" (str/join ", " available-tags)))
               ""
               (when (seq tag-samples)
                 (str "CONTENT INVENTORY:\n"
                      (tag-samples->description tag-samples)
                      "\n"))
               ""
               "INSTRUCTION FORMAT:"
               "Respond with a JSON object containing exactly these keys:"
               "  reasoning: string (why you made this change)"
               "  operations: array of operation objects"
               "  preview: { affected_blocks: string[], description: string }"
               ""
               "OPERATION TYPES:"
               "  { type: 'update_slot', slot_index: number, changes: { required_tags?: string[], excluded_tags?: string[], playback_order?: string, fill_mode?: string } }"
               "  { type: 'replace_slot', slot_index: number, new_slot: { time?: string, fill_mode: string, required_tags?: string[], ... } }"
               "  { type: 'add_slot', slot: { time: string, fill_mode: string, required_tags: string[], ... } }"
               "  { type: 'delete_slot', slot_index: number }"
               "  { type: 'shuffle_slot', slot_index: number }"
               "  { type: 'rebuild' }"
               ""
               "RULES:"
               "  • Only modify the blocks the user explicitly mentions."
               "  • Use existing tags when possible."
               "  • Keep the schedule structure intact unless asked to change it."
               "  • If the instruction is ambiguous, make a reasonable choice and explain it."
               "  • If you cannot fulfill the request, return an empty operations array and explain why."
               ""])))

;; ---------------------------------------------------------------------------
;; Operation execution
;; ---------------------------------------------------------------------------

(defn- execute-operation!
  "Execute a single parsed operation against Pseudovision.

   Args:
     pv-config   — PV client config
     channel-id  — PV channel ID
     schedule-id — Current schedule ID
     op          — Parsed operation map

   Returns:
     {:success true/false :operation op :result ... :error ...}"
  [pv-config channel-id schedule-id op]
  ;; TODO: The PV slot field names (:slot-index, :id, :playback-order, …) are a
  ;; best-guess kebab-case mapping consistent with slot-spec->pseudovision-slot
  ;; and the "kebab-case everywhere" refactor. Verify against a live
  ;; GET /api/schedules/:id/slots response and adjust if PV actually emits
  ;; snake_case or namespaced keys.
  (try
    (case (:type op)
      "update_slot"
      (let [slot-idx (:slot_index op)
            changes (:changes op)
            ;; Find the slot by index
            slots (pv/list-slots pv-config schedule-id)
            target (first (filter #(= (:slot-index %) slot-idx) slots))]
        (if target
          (let [slot-id (:id target)
                pv-changes (cond-> {}
                             (:required_tags changes) (assoc :required-tags (:required_tags changes))
                             (:excluded_tags changes) (assoc :excluded-tags (:excluded_tags changes))
                             (:playback_order changes) (assoc :playback-order (:playback_order changes))
                             (:fill_mode changes) (assoc :fill-mode (:fill_mode changes)))]
            (pv/update-slot! pv-config schedule-id slot-id pv-changes)
            {:success true :operation op :result :updated})
          {:success false :operation op :error (str "Slot index " slot-idx " not found")}))

      "replace_slot"
      (let [slot-idx (:slot_index op)
            new-slot (:new_slot op)
            slots (pv/list-slots pv-config schedule-id)
            target (first (filter #(= (:slot-index %) slot-idx) slots))]
        (if target
          (do
            (pv/delete-slot! pv-config schedule-id (:id target))
            (pv/add-slot! pv-config schedule-id (pv-schedule/slot-spec->pseudovision-slot new-slot slot-idx))
            {:success true :operation op :result :replaced})
          {:success false :operation op :error (str "Slot index " slot-idx " not found")}))

      "delete_slot"
      (let [slot-idx (:slot_index op)
            slots (pv/list-slots pv-config schedule-id)
            target (first (filter #(= (:slot-index %) slot-idx) slots))]
        (if target
          (do
            (pv/delete-slot! pv-config schedule-id (:id target))
            {:success true :operation op :result :deleted})
          {:success false :operation op :error (str "Slot index " slot-idx " not found")}))

      "add_slot"
      (let [new-slot (:slot op)
            slots (pv/list-slots pv-config schedule-id)
            next-idx (count slots)]
        (pv/add-slot! pv-config schedule-id (pv-schedule/slot-spec->pseudovision-slot new-slot next-idx))
        {:success true :operation op :result :added})

      "shuffle_slot"
      (let [slot-idx (:slot_index op)
            slots (pv/list-slots pv-config schedule-id)
            target (first (filter #(= (:slot-index %) slot-idx) slots))]
        (if target
          (let [slot-id (:id target)
                current-order (:playback-order target)
                new-order (case current-order
                            "shuffle" "random"
                            "random" "chronological"
                            "chronological" "shuffle"
                            "shuffle")]
            (pv/update-slot! pv-config schedule-id slot-id {:playback-order new-order})
            {:success true :operation op :result :shuffled :new_order new-order})
          {:success false :operation op :error (str "Slot index " slot-idx " not found")}))

      "rebuild"
      (do
        (pv/rebuild-playout! pv-config channel-id {:from "now" :horizon 14})
        {:success true :operation op :result :rebuilt})

      ;; Unknown operation type
      {:success false :operation op :error (str "Unknown operation type: " (:type op))})
    (catch Exception e
      (log/error e "Operation failed" {:operation op})
      {:success false :operation op :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn process-intent!
  "Process a natural-language scheduling instruction.

   Args:
     llm-config   — LLM client config (see tunarr.scheduler.llm)
     pv-config    — PV client config
     channel-id   — PV channel ID
     instruction  — Natural language string
     opts         — {:dry-run? false :available-tags [] :horizon 14}

   Returns:
     {:success true/false
      :reasoning string
      :operations [{:success true/false :operation ... :result ... :error ...}]
      :preview {:affected_blocks [...] :description string}
      :applied? true/false}"
  [llm-config pv-config channel-id instruction & {:keys [dry-run? available-tags tag-samples horizon]
                                                     :or {dry-run? false horizon 14}}]
  (log/info "Processing scheduling intent"
            {:channel-id channel-id :instruction instruction :dry-run? dry-run?})

  ;; 1. Fetch current schedule
  (let [channel (pv/get-channel pv-config channel-id)
        ;; TODO: verify PV channel field names against a live response (see
        ;; TODO in execute-operation!). Best-guess kebab-case.
        schedule-id (:schedule-id channel)
        schedule (when schedule-id
                   (pv/get-schedule pv-config schedule-id))
        slots (when schedule-id
                (pv/list-slots pv-config schedule-id))
        schedule-with-slots (assoc schedule :slots slots)

        ;; 2. Build prompt
        prompt (build-context-prompt (:name channel) schedule-with-slots available-tags tag-samples)
        messages [{:role "system" :content prompt}
                  {:role "user" :content instruction}]

        ;; 3. Call LLM
        llm-response (llm/chat-completion! llm-config messages)
        parsed (try
                 (json/parse-string (:content llm-response) true)
                 (catch Exception e
                   (log/error e "Failed to parse LLM response as JSON"
                              {:raw (:content llm-response)})
                   {:reasoning "Failed to parse LLM response"
                    :operations []
                    :preview {:affected_blocks [] :description "Parse error"}}))

        ;; 4. Extract operations
        operations (get parsed :operations [])
        reasoning (get parsed :reasoning "")
        preview (get parsed :preview {:affected_blocks [] :description ""})]

    (log/info "LLM intent response"
              {:reasoning reasoning
               :operations (count operations)
               :preview preview})

    ;; 5. Execute or preview
    (if dry-run?
      {:success true
       :reasoning reasoning
       :operations (mapv #(assoc % :dry-run true) operations)
       :preview preview
       :applied? false}

      ;; Apply operations
      (let [results (mapv #(execute-operation! pv-config channel-id schedule-id %) operations)
            all-success? (every? :success results)]
           ;; Rebuild playout after modifications — use "horizon" to extend
           ;; the timeline forward without resetting the cursor, preserving
           ;; sequential episode positions.
           (when (and all-success? (seq results))
             (pv/rebuild-playout! pv-config channel-id {:from "horizon" :horizon horizon}))

        {:success all-success?
         :reasoning reasoning
         :operations results
         :preview preview
         :applied? true}))))
