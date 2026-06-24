(ns tunarr.scheduler.http.api.channels
  "HTTP handlers for channel operations."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.channels.sync :as channel-sync]
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
