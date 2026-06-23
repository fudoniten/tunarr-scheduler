(ns tunarr.scheduler.http.api.intent
  "HTTP handlers for natural-language scheduling intents.

   This is the primary interface for LLM agents (Hermes) to modify
   channel schedules via natural language instructions."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.intent :as intent]
            [tunarr.scheduler.media.catalog :as catalog]))

(defn intent-handler
  "POST /api/channels/:channel-id/intent

   Accepts a natural-language instruction and translates it into
   schedule operations via LLM.

   Body:
     {:instruction 'Replace the 6pm block with Cheers'
      :dry-run true               ; optional, default false
      :horizon 14}                ; optional

   Response:
     {:success true
      :reasoning '...'
      :operations [{:success true :type 'update_slot' :result 'updated'}]
      :preview {:affected_blocks ['Tue 18:00']
               :description 'Now plays Cheers'}}"
  [{:keys [pseudovision llm catalog]}]
  (fn [req]
    (try
      (let [channel-id   (get-in req [:parameters :path :channel-id])
            instruction  (get-in req [:parameters :body :instruction])
            dry-run?     (get-in req [:parameters :body :dry-run] false)
            horizon      (get-in req [:parameters :body :horizon] 14)
            ;; Fetch tag samples from catalog for richer context
            tag-samples  (when catalog
                           (try (catalog/get-tag-samples catalog)
                                (catch Exception e
                                  (log/warn e "Could not fetch tag samples for intent")
                                  [])))]
        (if (seq instruction)
          (let [result (intent/process-intent! llm pseudovision channel-id instruction
                                                  {:dry-run? dry-run?
                                                   :horizon horizon
                                                   :tag-samples tag-samples})]
            {:status (if (:success result) 200 422)
             :body result})
          {:status 400 :body {:error "Missing required field: :instruction"}}))
      (catch Exception e
        (log/error e "Intent processing failed")
        {:status 500 :body {:error (.getMessage e)
                           :type (str (class e))}}))))
