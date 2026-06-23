(ns tunarr.scheduler.http.api.channels
  "HTTP handlers for channel operations."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.channels.sync :as channel-sync]
            [tunarr.scheduler.scheduling.pseudovision :as pv-schedule]
            [tunarr.scheduler.scheduling.templates :as templates]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn sync-channels-handler
  "Sync all channels to Pseudovision."
  [{:keys [pseudovision channels]}]
  (fn [_]
    (try
      (let [result (channel-sync/sync-all-channels! pseudovision channels)]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error syncing channels to Pseudovision")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-schedule-handler
  "Get current schedule for a channel from Pseudovision.

   Returns schedule + slots + upcoming playout events."
  [{:keys [pseudovision]}]
  (fn [req]
    (try
      (let [channel-id (get-in req [:parameters :path :channel-id])
            channel    (pv-client/get-channel pseudovision channel-id)
            schedule-id (:schedule-id channel)]
        (if-not schedule-id
          {:status 404
           :body   {:error (str "Channel " channel-id " has no schedule attached")}}
          (let [schedule (pv-client/get-schedule pseudovision schedule-id)
                slots    (pv-client/list-slots pseudovision schedule-id)
                events   (try (pv-client/list-playout-events pseudovision channel-id)
                              (catch Exception e
                                (log/warn e "Could not fetch playout events" {:channel-id channel-id})
                                []))]
            {:status 200
             :body   {:channel-id   channel-id
                      :channel-name (:name channel)
                      :schedule-id  schedule-id
                      :schedule     schedule
                      :slots        slots
                      :upcoming-events (take 10 events)}})))
      (catch Exception e
        (log/error e "Error getting channel schedule")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn update-schedule-handler
  "Update channel schedule in Pseudovision."
  [{:keys [pseudovision]}]
  (fn [req]
    (try
      (let [channel-id   (get-in req [:parameters :path :channel-id])
            channel-spec (get-in req [:parameters :body])
            horizon      (get channel-spec :horizon 14)
            result       (pv-schedule/update-channel-schedule!
                          pseudovision
                          channel-id
                          channel-spec
                          {:horizon horizon})]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error creating channel schedule")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn apply-template-handler
  "Apply a schedule template to a single channel.

   The request body may contain:
   • :template — a full template map (name + slots)
   • :template-key — a keyword to look up in default-templates (e.g. :sitcom-spectrum)
   • :horizon — optional, defaults to 14

   If neither :template nor :template-key is supplied, returns 400."
  [{:keys [pseudovision]}]
  (fn [req]
    (try
      (let [channel-id   (get-in req [:parameters :path :channel-id])
            template     (get-in req [:parameters :body :template])
            template-key (get-in req [:parameters :body :template-key])
            horizon      (get-in req [:parameters :body :horizon] 14)
            result       (cond
                           template
                           (templates/apply-template! pseudovision channel-id template {:horizon horizon})

                           template-key
                           (templates/apply-template-by-name! pseudovision channel-id template-key {:horizon horizon})

                           :else
                           nil)]
        (if result
          {:status 200 :body result}
          {:status 400 :body {:error "Request must include :template or :template-key"}}))
      (catch Exception e
        (log/error e "Error applying template to channel")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn apply-all-templates-handler
  "Apply default templates to all configured channels."
  [{:keys [pseudovision channels]}]
  (fn [_]
    (try
      (let [result (templates/apply-templates-to-channels! pseudovision channels)]
        {:status 200 :body result})
      (catch Exception e
        (log/error e "Error applying templates to all channels")
        {:status 500 :body {:error (.getMessage e)}}))))
