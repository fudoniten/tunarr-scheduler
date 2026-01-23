(ns tunarr.scheduler.backends.tunarr.client
  "Tunarr backend client implementation.
  
  This namespace provides integration with Tunarr. Since Tunarr's API
  is still evolving, this will initially focus on export/recommendation
  functionality rather than direct API integration."
  (:require [tunarr.scheduler.backends.protocol :as proto]
            [taoensso.timbre :as log]))

(defrecord TunarrBackend [config]
  proto/ChannelBackend
  
  (create-channel [_ channel-spec]
    (log/warn "Tunarr create-channel not yet implemented (manual export mode)")
    {:error "Not implemented - Tunarr uses manual export"})
  
  (update-channel [_ channel-id updates]
    (log/warn "Tunarr update-channel not yet implemented (manual export mode)")
    {:error "Not implemented - Tunarr uses manual export"})
  
  (delete-channel [_ channel-id]
    (log/warn "Tunarr delete-channel not yet implemented (manual export mode)")
    {:success false :message "Not implemented - Tunarr uses manual export"})
  
  (get-channels [_]
    (log/warn "Tunarr get-channels not yet implemented (manual export mode)")
    [])
  
  (upload-schedule [_ channel-id schedule]
    (log/warn "Tunarr upload-schedule not yet implemented (manual export mode)")
    {:success false :message "Not implemented - Tunarr uses manual export"})
  
  (get-schedule [_ channel-id]
    (log/warn "Tunarr get-schedule not yet implemented (manual export mode)")
    nil)
  
  (validate-config [_ config]
    (let [base-url (:base-url config)]
      (if (and base-url (string? base-url) (not (empty? base-url)))
        {:valid? true}
        {:valid? false
         :errors ["base-url is required and must be a non-empty string"]}))))

(defn create
  "Create a Tunarr backend client.
  
  Args:
    config - Configuration map with :base-url and other settings
  
  Returns:
    Tunarr backend implementation"
  [config]
  (log/info "Creating Tunarr backend client (manual export mode)" {:base-url (:base-url config)})
  (->TunarrBackend config))
