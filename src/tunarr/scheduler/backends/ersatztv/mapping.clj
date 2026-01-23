(ns tunarr.scheduler.backends.ersatztv.mapping
  "Mapping functions between universal channel specs and ErsatzTV API format.
  
  This namespace handles bidirectional conversion between our backend-agnostic
  channel specifications and ErsatzTV's specific API format."
  (:require [tunarr.scheduler.channels :as ch]
            [clojure.string :as str]))

;; Streaming mode mappings
(def streaming-mode->ersatz
  "Map from universal streaming mode to ErsatzTV format"
  {:hls-segmenter "HLS Segmenter"
   :mpeg-ts "MPEG-TS"
   :hls-direct "HLS Direct"
   :mpeg-ts-legacy "MPEG-TS Legacy"})

(def ersatz->streaming-mode
  "Map from ErsatzTV format to universal streaming mode"
  (into {} (map (fn [[k v]] [v k]) streaming-mode->ersatz)))

;; Watermark position mappings
(def watermark-position->ersatz
  {:top-left "TopLeft"
   :top-right "TopRight"
   :bottom-left "BottomLeft"
   :bottom-right "BottomRight"})

(def ersatz->watermark-position
  (into {} (map (fn [[k v]] [v k]) watermark-position->ersatz)))

(defn channel-spec->ersatztv
  "Convert a universal channel specification to ErsatzTV API format.
  
  Args:
    channel-spec - Universal channel specification map
  
  Returns:
    Map in ErsatzTV API format suitable for POST/PUT requests"
  [channel-spec]
  (let [base {:number (ch/channel-number->string (::ch/channel-number channel-spec))
              :name (::ch/channel-name channel-spec)}
        ;; Add optional fields if present
        with-desc (if-let [desc (::ch/channel-description channel-spec)]
                    (assoc base :description desc)
                    base)
        with-logo (if-let [logo (::ch/channel-logo-url channel-spec)]
                    (assoc with-desc :logo logo)
                    with-desc)
        with-streaming (if-let [mode (::ch/streaming-mode channel-spec)]
                         (assoc with-logo :streamingMode (get streaming-mode->ersatz mode "HLS Segmenter"))
                         with-logo)
        with-group (if-let [group (::ch/channel-group channel-spec)]
                     (assoc with-streaming :group group)
                     with-streaming)
        with-audio (if-let [lang (::ch/preferred-audio-language channel-spec)]
                     (assoc with-group :preferredAudioLanguage lang)
                     with-group)
        ;; Handle watermark config if present
        with-watermark (if-let [wm-config (::ch/watermark-config channel-spec)]
                         (let [{::ch/keys [watermark-enabled watermark-url
                                          watermark-position watermark-opacity
                                          watermark-size]} wm-config]
                           (cond-> with-audio
                             watermark-enabled (assoc :watermarkEnabled true)
                             watermark-url (assoc :watermarkPath watermark-url)
                             watermark-position (assoc :watermarkLocation
                                                      (get watermark-position->ersatz
                                                           watermark-position
                                                           "BottomRight"))
                             watermark-opacity (assoc :watermarkOpacity watermark-opacity)
                             watermark-size (assoc :watermarkSize watermark-size)))
                         with-audio)
        ;; Add any ErsatzTV-specific settings
        with-ersatz-specific (if-let [ersatz-mode (::ch/ersatz-streaming-mode channel-spec)]
                               (assoc with-watermark :streamingMode ersatz-mode)
                               with-watermark)]
    with-ersatz-specific))

(defn ersatztv->channel-spec
  "Convert an ErsatzTV channel to universal channel specification.
  
  Args:
    ersatz-channel - Channel data from ErsatzTV API
  
  Returns:
    Universal channel specification map"
  [ersatz-channel]
  (let [id (:id ersatz-channel)
        number (:number ersatz-channel)
        name (:name ersatz-channel)
        ;; Parse channel number - could be integer or decimal string
        parsed-number (if (string? number)
                        (if (str/includes? number ".")
                          number
                          (Integer/parseInt number))
                        number)
        base {::ch/channel-id (keyword (str "ch-" id))
              ::ch/channel-name name
              ::ch/channel-number parsed-number}
        ;; Add optional fields
        with-desc (if-let [desc (:description ersatz-channel)]
                    (assoc base ::ch/channel-description desc)
                    base)
        with-logo (if-let [logo (:logo ersatz-channel)]
                    (assoc with-desc ::ch/channel-logo-url logo)
                    with-desc)
        with-streaming (if-let [mode (:streamingMode ersatz-channel)]
                         (if-let [universal-mode (get ersatz->streaming-mode mode)]
                           (assoc with-logo ::ch/streaming-mode universal-mode)
                           with-logo)
                         with-logo)
        with-group (if-let [group (:group ersatz-channel)]
                     (assoc with-streaming ::ch/channel-group group)
                     with-streaming)
        with-audio (if-let [lang (:preferredAudioLanguage ersatz-channel)]
                     (assoc with-group ::ch/preferred-audio-language lang)
                     with-group)
        ;; Handle watermark config
        with-watermark (if (:watermarkEnabled ersatz-channel)
                         (let [wm-config (cond-> {::ch/watermark-enabled true}
                                           (:watermarkPath ersatz-channel)
                                           (assoc ::ch/watermark-url (:watermarkPath ersatz-channel))
                                           (:watermarkLocation ersatz-channel)
                                           (assoc ::ch/watermark-position
                                                  (get ersatz->watermark-position
                                                       (:watermarkLocation ersatz-channel)
                                                       :bottom-right))
                                           (:watermarkOpacity ersatz-channel)
                                           (assoc ::ch/watermark-opacity (:watermarkOpacity ersatz-channel))
                                           (:watermarkSize ersatz-channel)
                                           (assoc ::ch/watermark-size (:watermarkSize ersatz-channel)))]
                           (assoc with-audio ::ch/watermark-config wm-config))
                         with-audio)
        ;; Store the original ErsatzTV ID for reference
        with-backend-id (assoc with-watermark
                               ::ersatz-id id
                               ::backend-settings {:ersatztv {:id id}})]
    with-backend-id))

(defn validate-ersatztv-channel
  "Validate that an ErsatzTV channel has required fields.
  
  Args:
    ersatz-channel - Channel data from ErsatzTV API
  
  Returns:
    Map with :valid? boolean and optional :errors sequence"
  [ersatz-channel]
  (let [errors (cond-> []
                 (not (:id ersatz-channel))
                 (conj "Missing required field: id")
                 (not (:name ersatz-channel))
                 (conj "Missing required field: name")
                 (not (:number ersatz-channel))
                 (conj "Missing required field: number"))]
    (if (empty? errors)
      {:valid? true}
      {:valid? false :errors errors})))
