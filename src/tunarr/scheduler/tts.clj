(ns tunarr.scheduler.tts
  "Text-to-speech client abstraction."
  (:require [taoensso.timbre :as log]))

(defmulti create-client (fn [{:keys [provider]}] (keyword provider)))

(defmethod create-client :default [config]
  (log/info "Initialising TTS client" {:provider (:provider config)})
  (assoc config :type :generic))

(defmethod create-client :mock [_]
  {:type :mock})

(defmulti close! (fn [client] (:type client)))

(defmethod close! :default [client]
  (log/info "Closing TTS client" {:type (:type client)})
  (when-let [close-fn (:close client)] (close-fn)))

(defn synthesize
  "Produce an audio asset for the provided script. Placeholder returns a path."
  [client {:keys [script voice]}]
  (log/info "Synthesising bumper audio" {:voice (or voice (:voice client))})
  {:path (str "audio/" (hash script) ".mp3")
   :voice (or voice (:voice client))})
