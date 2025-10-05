(ns tunarr.scheduler.bumpers
  "Bumper generation orchestrating LLM script generation and TTS synthesis."
  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.llm :as llm]
            [tunarr.scheduler.tts :as tts]))

(defn create-service [{:keys [llm tts]}]
  (log/info "Initialising bumper service")
  {:llm llm
   :tts tts})

(defn close! [_]
  (log/info "Closing bumper service"))

(defn generate-bumper!
  "Generate bumper metadata and audio stub."
  [{:keys [llm tts]} {:keys [channel upcoming]}]
  (let [script (llm/generate-bumper-script llm {:channel channel :upcoming upcoming})
        audio (tts/synthesize tts {:script script})]
    {:script script
     :audio audio}))
