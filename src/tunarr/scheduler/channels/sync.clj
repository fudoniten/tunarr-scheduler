(ns tunarr.scheduler.channels.sync
  "Channel synchronization between tunarr-scheduler config and Pseudovision.
   
   Ensures that channels defined in tunarr-scheduler's config.edn exist
   in Pseudovision with matching UUIDs, names, and numbers."
  (:require [tunarr.scheduler.backends.pseudovision.client :as pv]
            [taoensso.timbre :as log]))

(defn- extract-channel-number
  "Extract channel number from channel name or generate from index.
   
   Examples:
     'Channel 555' → '555'
     'Enigma TV' → nil (will use 900 + index)"
  [name]
  (when-let [match (re-find #"Channel\s+(\d+)" name)]
    (second match)))

(defn- generate-channel-number
  "Generate a channel number for a channel without one.
   Uses 2-15 range for primary channels (14 channels total)."
  [idx]
  (str (+ 2 idx)))

(defn- channel-spec->pseudovision
  "Convert tunarr-scheduler channel spec to Pseudovision format.
   
   Input (from config.edn):
     {:name 'Enigma TV'
      :id '321b6f56-96bb-49bd-b826-72cbfcb786c6'
      :description 'Mystery shows and movies...'}
   
   Output (Pseudovision API):
     {:name 'Enigma TV'
      :uuid '321b6f56-96bb-49bd-b826-72cbfcb786c6'
      :number '900'
      :description 'Mystery shows and movies...'}"
  [_ channel-spec idx]
  (let [number (or (extract-channel-number (:name channel-spec))
                   (generate-channel-number idx))]
    {:name (:name channel-spec)
     :uuid (str (:id channel-spec))
     :number number
     :description (:description channel-spec)}))

(defn- channel-uuid [ch] (or (:uuid ch) (:channels/uuid ch)))
(defn- channel-id   [ch] (or (:id ch)   (:channels/id ch)))
(defn- channel-name [ch] (or (:name ch) (:channels/name ch)))
(defn- channel-num  [ch] (or (:number ch) (:channels/number ch)))

(defn- find-channel-by-uuid
  "Find a Pseudovision channel by UUID. Compares stringified UUIDs so a parsed
   java.util.UUID matches the string the API returns. The /api/channels records
   use plain :uuid keys (table-qualified :channels/uuid accepted defensively)."
  [pv-channels uuid]
  (first (filter #(= (str (channel-uuid %)) (str uuid)) pv-channels)))

(defn sync-channel!
  "Sync a single channel to Pseudovision.
   
   Creates if missing, updates if exists.
   Returns :created, :updated, or :error"
  [pv-config channel-key channel-spec idx]
  (try
    (let [pv-spec (channel-spec->pseudovision channel-key channel-spec idx)
          uuid (-> pv-spec :uuid (parse-uuid))
          existing-channels (pv/list-channels pv-config)
          existing (find-channel-by-uuid existing-channels uuid)]

      (if existing
        ;; Channel exists - check if update needed
        (let [needs-update? (or (not= (:name pv-spec) (channel-name existing))
                                (not= (:number pv-spec) (str (channel-num existing))))]
          (if needs-update?
            (do
              (log/info "Updating Pseudovision channel"
                        {:channel channel-key :uuid uuid :changes pv-spec})
              (pv/update-channel! pv-config (channel-id existing) pv-spec)
              {:status :updated :channel-id (channel-id existing)})
            (do
              (log/debug "Channel already synced" {:channel channel-key :uuid uuid})
              {:status :unchanged :channel-id (channel-id existing)})))

        ;; Channel doesn't exist - create it
        (do
          (log/info "Creating Pseudovision channel"
                    {:channel channel-key :spec pv-spec})
          (let [created (pv/create-channel! pv-config pv-spec)]
            {:status :created :channel-id (channel-id created)}))))

    (catch Exception e
      (log/error e "Failed to sync channel" {:channel channel-key})
      {:status :error :error (.getMessage e)})))

(defn sync-all-channels!
  "Sync all configured channels to Pseudovision.
   
   Args:
     pv-client - Pseudovision backend client (PseudovisionBackend record) or config map
     channels - Map of channel-key → channel-spec from config
   
   Returns:
     Map with :created, :updated, :unchanged, :errors counts and details"
  [pv-client channels]
  ;; Extract config from client if it's a record, otherwise use as-is
  (let [pv-config (pv/get-config pv-client)]
    (log/info "Syncing channels to Pseudovision"
              {:count (count channels)
               :config pv-config})
    (let [results (map-indexed
                   (fn [idx [channel-key channel-spec]]
                     (let [result (sync-channel! pv-config channel-key channel-spec idx)]
                       (assoc result :channel channel-key)))
                   channels)
          by-status (group-by :status results)]
      {:created (count (get by-status :created))
       :updated (count (get by-status :updated))
       :unchanged (count (get by-status :unchanged))
       :pending (count (get by-status :pending))
       :errors (count (get by-status :error))
        :details (vec results)})))

(defn- channels-from-config
  "Extract channels map from system config."
  [config]
  (get config :channels {}))

(defn sync-on-startup!
  "Sync channels to Pseudovision during system initialization.
   
   Called from system.clj init-key."
  [pv-config channels]
  (when (:auto-sync pv-config)
    (log/info "Auto-sync enabled - syncing channels to Pseudovision")
    (let [result (sync-all-channels! pv-config channels)]
      (log/info "Channel sync complete" result)
      result)))
