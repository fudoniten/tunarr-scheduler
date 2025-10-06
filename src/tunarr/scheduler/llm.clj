(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require
   [taoensso.timbre :as log]))

(defprotocol LLMClient
  "Protocol describing the interactions supported by LLM providers."
  (classify-media! [client media]
    "Classify a media entity by delegating to the configured LLM.")
  (schedule-programming! [client request]
    "Generate a schedule via the LLM. Placeholder implementation.")
  (generate-bumper-script [client request]
    "Generate narration script for bumpers using the LLM.")
  (close! [client]
    "Clean up any LLM client resources."))

(defmulti create-client
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defrecord GenericLLMClient [provider close-fn]
  LLMClient
  (classify-media! [_ media]
    (log/info "Classifying media" {:title (:name media) :type :generic})
    {:tags #{"unspecified"}
     :channels [:general]
     :kid-friendly? false})
  (schedule-programming! [_ request]
    (log/info "Scheduling programming via LLM" {:request request :type :generic})
    {:slots []})
  (generate-bumper-script [_ {:keys [channel upcoming]}]
    (log/info "Generating bumper script" {:channel channel :type :generic})
    (format "Up next on %s: %s" channel (or upcoming "More great content!")))
  (close! [_]
    (log/info "Closing LLM client" {:type :generic :provider provider})
    (when close-fn
      (close-fn))))

(defmethod create-client :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (->GenericLLMClient (:provider config) (:close config)))

(defmethod create-client :mock [_]
  (->GenericLLMClient :mock nil))
