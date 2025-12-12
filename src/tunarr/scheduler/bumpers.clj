(ns tunarr.scheduler.bumpers
  "Bumper generation orchestrating script generation and TTS synthesis."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.tts :as tts]))

(defn create-service [{:keys [tunabrain tts]}]
  (log/info "Initialising bumper service")
  {:tunabrain tunabrain
   :tts       tts})

(defn close! [_]
  (log/info "Closing bumper service"))

#_(defn generate-bumper!
  "Generate bumper metadata and audio stub."
  [{:keys [tunabrain tts]} {:keys [channel upcoming]}]
  (let [script (:script (tunabrain/request-tags! tunabrain {:channel channel :upcoming upcoming}))
        audio (tts/synthesize tts {:script script})]
    {:script script
     :audio audio}))
