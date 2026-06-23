(ns tunarr.scheduler.scheduling.pseudovision
  "Schedule generation for Pseudovision channels.
   
   Converts high-level channel specs (from LLM or config) into
   Pseudovision schedule + slot definitions."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; Schedule Spec → Pseudovision API Translation
;; ---------------------------------------------------------------------------

(defn slot-spec->pseudovision-slot
  "Convert a schedule slot spec to Pseudovision API format.
   
   Input slot spec:
     {:time '18:00:00'                ; Optional - for fixed anchors
      :duration-hours 2                 ; Optional - for block mode
      :fill-mode 'flood'               ; once/count/block/flood
      :collection-id 1                 ; Optional
      :required-tags [:comedy :short]  ; Optional - filter items
      :excluded-tags [:explicit]       ; Optional - exclude items
      :playback-order 'shuffle'        ; chronological/random/shuffle/semi-sequential
      :batch-size 5}                   ; For semi-sequential mode
   
   Output Pseudovision format:
     {:slot-index N
      :anchor 'fixed'/'sequential'
      :start-time '18:00:00'
      :fill-mode 'flood'
      :block-duration 'PT2H'           ; ISO-8601 duration
      :collection-id 1
      :required-tags ['comedy' 'short']
      :excluded-tags ['explicit']
      :playback-order 'shuffle'
      :marathon-batch-size 5}"
  [slot-spec idx]
  (cond-> {:slot-index idx
           :fill-mode (name (or (:fill-mode slot-spec) :flood))
           :playback-order (name (or (:playback-order slot-spec) :shuffle))}

    ;; Anchor type
    (:time slot-spec)
    (assoc :anchor "fixed" :start-time (:time slot-spec))

    (not (:time slot-spec))
    (assoc :anchor "sequential")

    ;; Block duration: raw ISO-8601 string takes priority
    (:block-duration slot-spec)
    (assoc :block-duration (:block-duration slot-spec))

    ;; Block duration: legacy hours → ISO-8601 conversion
    (:duration-hours slot-spec)
    (assoc :block-duration (str "PT" (:duration-hours slot-spec) "H"))

    ;; Item count for count mode
    (:item-count slot-spec)
    (assoc :item-count (:item-count slot-spec))

    ;; Content source
    (:collection-id slot-spec)
    (assoc :collection-id (:collection-id slot-spec))

    (:media-item-id slot-spec)
    (assoc :media-item-id (:media-item-id slot-spec))

    ;; Tag filters (convert keywords to strings)
    (seq (:required-tags slot-spec))
    (assoc :required-tags (mapv name (:required-tags slot-spec)))

    (seq (:excluded-tags slot-spec))
    (assoc :excluded-tags (mapv name (:excluded-tags slot-spec)))

    ;; Semi-sequential batch size
    (:batch-size slot-spec)
    (assoc :marathon-batch-size (:batch-size slot-spec))

    ;; Day-of-week bitmask (integer, e.g. 21 for MWF)
    (:days-of-week slot-spec)
    (assoc :days-of-week (:days-of-week slot-spec))))

;; ---------------------------------------------------------------------------
;; Schedule Generation
;; ---------------------------------------------------------------------------

(defn create-schedule-for-channel!
  "Create a complete schedule in Pseudovision for a channel.
   
   Args:
     pv-config - Pseudovision client config
     channel-spec - Map with:
       :name - Schedule name
       :slots - Vector of slot specs (see slot-spec->pseudovision-slot)
   
   Returns:
     Map with :schedule-id and :slots-created"
  [pv-config channel-spec]
  (log/info "Creating Pseudovision schedule" 
            {:name (:name channel-spec)
             :slots (count (:slots channel-spec))})
  
  ;; Create the schedule
  (let [schedule (pv/create-schedule! pv-config 
                   {:name (:name channel-spec)})
        schedule-id (:schedules/id schedule)]
    
    (log/info "Created schedule" {:schedule-id schedule-id})
    
    ;; Create slots
    (doseq [[idx slot-spec] (map-indexed vector (:slots channel-spec))]
      (let [pv-slot (slot-spec->pseudovision-slot slot-spec idx)]
        (log/debug "Creating slot" {:index idx :slot pv-slot})
        (pv/add-slot! pv-config schedule-id pv-slot)))
    
    (log/info "Schedule created successfully" 
              {:schedule-id schedule-id 
               :slots (count (:slots channel-spec))})
    
    {:schedule-id schedule-id
     :slots-created (count (:slots channel-spec))}))

(defn assign-and-rebuild!
  "Assign a schedule to a channel and trigger playout rebuild.
   
   Args:
     pv-config - Pseudovision client config
     channel-id - Pseudovision channel ID
     schedule-id - Schedule ID to assign
     opts - Options map with :horizon (days, default 14)
   
   Returns:
     Map with :events-generated and :horizon-days"
  [pv-config channel-id schedule-id opts]
  (log/info "Assigning schedule to channel" 
            {:channel-id channel-id :schedule-id schedule-id})
  
  ;; Attach schedule to channel
  (pv/update-channel! pv-config channel-id {:schedule-id schedule-id})
  
  ;; Trigger rebuild
  (let [horizon (get opts :horizon 14)
        result (pv/rebuild-playout! pv-config channel-id {:from "now" :horizon horizon})]
    
    (log/info "Playout rebuild complete" 
              {:channel-id channel-id
               :events-generated (:events-generated result)})
    
    result))

(defn update-channel-schedule!
  "End-to-end: create schedule and assign to channel.
   
   This is the main entry point for generating and deploying a schedule.
   
   Args:
     pv-config - Pseudovision client config
     channel-id - Channel to update
     channel-spec - Channel spec with :name and :slots
     opts - Options with :horizon
   
   Returns:
     Map with :schedule-id, :events-generated"
  [pv-config channel-id channel-spec opts]
  (let [{:keys [schedule-id]} (create-schedule-for-channel! pv-config channel-spec)
        rebuild-result (assign-and-rebuild! pv-config channel-id schedule-id opts)]
    
    (merge {:schedule-id schedule-id}
           rebuild-result)))

;; ---------------------------------------------------------------------------
;; Example Schedule Specs
;; ---------------------------------------------------------------------------

(comment
  ;; Example 1: Simple comedy channel
  (def comedy-channel
    {:name "Comedy Central"
     :slots [{:time "18:00:00"
              :duration-hours 2
              :fill-mode :block
              :required-tags [:comedy :short]
              :excluded-tags [:explicit]
              :playback-order :shuffle}
             {:time "20:00:00"
              :fill-mode :flood
              :required-tags [:sitcom]
              :playback-order :semi-sequential
              :batch-size 3}]})
  
  ;; Example 2: All-day programming with tag filters
  (def family-channel
    {:name "Family Friendly 24/7"
     :slots [{:time "06:00:00"
              :duration-hours 3
              :fill-mode :block
              :required-tags [:kids :animated]
              :playback-order :shuffle}
             {:time "09:00:00"
              :duration-hours 11
              :fill-mode :block
              :required-tags [:family-friendly :daytime]
              :excluded-tags [:violence :scary]
              :playback-order :random}
             {:time "20:00:00"
              :fill-mode :flood
              :required-tags [:family-friendly :primetime]
              :playback-order :chronological}]})
  
  ;; Example 3: Simple sequential schedule
  (def sitcom-marathon
    {:name "Sitcom Marathon"
     :slots [{:fill-mode :flood
              :collection-id 5
              :playback-order :season-episode}]}))
