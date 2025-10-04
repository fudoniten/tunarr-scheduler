(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require [taoensso.timbre :as log]))

(defmulti create-client
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defmethod create-client :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (assoc config :type :generic))

(defmethod create-client :mock [_]
  {:type :mock})

(defmulti close!
  "Clean up any LLM client resources."
  (fn [client] (:type client)))

(defmethod close! :default [client]
  (log/info "Closing LLM client" {:type (:type client)})
  (when-let [close-fn (:close client)]
    (close-fn)))

(defn classify-media!
  "Classify a media entity by delegating to the configured LLM.
   The skeleton implementation returns placeholder tags."
  [{:keys [type]} media]
  (log/info "Classifying media" {:title (:name media) :type type})
  {:tags #{"unspecified"}
   :channels [:general]
   :kid-friendly? false})

(defn schedule-programming!
  "Generate a schedule via the LLM. Placeholder implementation." [client request]
  (log/info "Scheduling programming via LLM" {:request request :type (:type client)})
  {:slots []})

(defn generate-bumper-script
  "Generate narration script for bumpers using the LLM."
  [client {:keys [type channel upcoming]}]
  (log/info "Generating bumper script" {:channel channel :type type})
  (format "Up next on %s: %s" channel (or upcoming "More great content!")))
