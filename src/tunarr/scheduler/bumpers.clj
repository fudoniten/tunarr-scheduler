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

;; TODO: Implement generate-bumper! to create bumper metadata and audio
;; Should request script from tunabrain and synthesize audio via TTS
