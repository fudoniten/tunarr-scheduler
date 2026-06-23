(ns tunarr.scheduler.scheduling.templates
  "Schedule template definitions and application for Pseudovision channels.

   A template is a high-level channel programming definition that gets
   translated into Pseudovision schedules + slots.  Templates define:

   • Background slots (flood fill, tag-based) — always playing
   • Special blocks (fixed time blocks, tag-based) — primetime variety
   • Day-of-week constraints on individual slots

   Example template:
     {:name 'Sitcom Spectrum Base'
      :slots [{:time '06:00:00'
               :fill-mode :flood
               :required-tags [:channel:spectrum]
               :playback-order :shuffle}
              {:time '15:00:00'
               :fill-mode :block
               :block-duration 'PT7H'
               :required-tags [:channel:spectrum :time-slot:primetime]
               :playback-order :shuffle}
              {:time '22:00:00'
               :fill-mode :flood
               :required-tags [:channel:spectrum]
               :playback-order :shuffle}]}

   The weekly executor (Phase 2) will extend this with episode tracking
   and sequential-show slots."
  (:require [tunarr.scheduler.scheduling.pseudovision :as pv-schedule]
            [tunarr.scheduler.backends.pseudovision.client :as pv]
            [tunarr.scheduler.media :as media]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Day-of-week helpers
;; ---------------------------------------------------------------------------

(def ^:private dow->bit
  {:mon 1 :tue 2 :wed 4 :thu 8 :fri 16 :sat 32 :sun 64})

(defn- days-of-week->bitmask
  "Convert a set/seq of day keywords to a PV bitmask.
   e.g. #{:mon :wed :fri} → 21"
  [days]
  (if (seq days)
    (reduce (fn [acc d] (bit-or acc (get dow->bit d 0))) 0 days)
    127))

;; ---------------------------------------------------------------------------
;; Template registry
;; ---------------------------------------------------------------------------

(defn- channel-template
  "Build a default 3-slot template for a channel.

   Structure:
   • 06:00 – 15:00  background (flood)
   • 15:00 – 22:00  special block (block, 7h)
   • 22:00 – 06:00  background (flood)

   The channel keyword is turned into a tag like :channel:spectrum."
  [channel-name & {:keys [extra-tags]}]
  (let [channel-tag (keyword (str "channel:" (name channel-name)))
        base-tags   (if extra-tags
                        (into [channel-tag] extra-tags)
                        [channel-tag])]
    {:name (str (name channel-name) " Base Programming")
     :slots [{:time "06:00:00"
              :fill-mode :flood
              :required-tags base-tags
              :playback-order :shuffle}
             {:time "15:00:00"
              :fill-mode :block
              :block-duration "PT7H"
              :required-tags (conj base-tags :time-slot:primetime)
              :playback-order :shuffle}
             {:time "22:00:00"
              :fill-mode :flood
              :required-tags base-tags
              :playback-order :shuffle}]}))

(def default-templates
  "Default templates for every channel type.  Keyed by the channel keyword
   used in the tunarr-scheduler config."
  {:enigma-tv     (channel-template :enigma-tv)
   :toon-town     (channel-template :toon-town)
   :galaxy        (channel-template :galaxy)
   :nippon-tv     (channel-template :nippon-tv)
   :sitcom-spectrum (channel-template :sitcom-spectrum)
   :britannia     (channel-template :britannia)
   :golden-reels  (channel-template :golden-reels)
   :spotlight     (channel-template :spotlight)
   :prime-series  (channel-template :prime-series)
   :info-bytes    (channel-template :info-bytes)
   :chronicles    (channel-template :chronicles)
   :muse          (channel-template :muse)
   :tasty-tv      (channel-template :tasty-tv)
   :hua-network   (channel-template :hua-network)})

;; ---------------------------------------------------------------------------
;; Template → slot translation
;; ---------------------------------------------------------------------------

(defn- transform-slot
  "Prepare a template slot for slot-spec->pseudovision-slot.
   Only needs to convert :days-of-week keyword set → integer bitmask;
   everything else is already in the shape expected by the translator."
  [slot-spec]
  (if (seq (:days-of-week slot-spec))
    (assoc slot-spec :days-of-week (days-of-week->bitmask (:days-of-week slot-spec)))
    slot-spec))

(defn- template->pv-spec
  "Convert a template map into the schedule spec shape consumed by
   create-schedule-for-channel!"
  [template]
  {:name (:name template)
   :slots (mapv transform-slot (:slots template))})

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn apply-template!
  "Apply a schedule template to a Pseudovision channel.

   Args:
     pv-config   — PseudovisionBackend record or raw config map
     channel-id  — Pseudovision channels.id (integer)
     template    — Template map (see default-templates)
     opts        — Optional: {:horizon 14}

   Returns:
     {:schedule-id N :events-generated M}"
  [pv-config channel-id template & {:keys [horizon] :or {horizon 14}}]
  (let [pv-spec (template->pv-spec template)]
    (log/info "Applying template to channel"
              {:channel-id channel-id
               :template-name (:name template)
               :slots (count (:slots template))})
    (pv-schedule/update-channel-schedule! pv-config channel-id pv-spec {:horizon horizon})))

(defn apply-template-by-name!
  "Look up a default template by channel keyword and apply it.

   Args:
     pv-config    — PseudovisionBackend record or raw config map
     channel-id   — Pseudovision channels.id
     channel-key  — Keyword like :sitcom-spectrum
     opts         — Optional: {:horizon 14}

   Returns:
     {:schedule-id N :events-generated M} or nil if no template found."
  [pv-config channel-id channel-key & {:keys [horizon] :or {horizon 14}}]
  (when-let [template (get default-templates channel-key)]
    (apply-template! pv-config channel-id template {:horizon horizon})))

(defn apply-templates-to-channels!
  "Apply default templates to a collection of channels.

   Fetches the current Pseudovision channel list and matches each config
   entry by UUID so the correct integer channel-id is used.

   Args:
     pv-config   — PseudovisionBackend record or raw config map
     channels    — Map of channel-key → {::media/channel-id UUID-string ...}

   Returns:
     Map of channel-key → result or :no-template / :no-channel-id / :not-found"
  [pv-config channels]
  (let [pv-channels (pv/list-channels pv-config)
        uuid->pv-id (into {} (map (fn [ch]
                                    [(str (:channels/uuid ch))
                                     (:channels/id ch)]))
                          pv-channels)]
    (into {}
          (for [[channel-key channel-cfg] channels]
            (let [cfg-uuid (get-in channel-cfg [::media/channel-id])
                  pv-channel-id (get uuid->pv-id (str cfg-uuid))]
              (cond
                (not cfg-uuid)
                [channel-key :no-channel-id]

                (not pv-channel-id)
                [channel-key :not-found]

                :else
                (try
                  (let [result (apply-template-by-name! pv-config pv-channel-id channel-key)]
                    (if result
                      [channel-key result]
                      [channel-key :no-template]))
                  (catch Exception e
                    (log/error e "Failed to apply template" {:channel channel-key})
                    [channel-key {:error (.getMessage e)}]))))))))
