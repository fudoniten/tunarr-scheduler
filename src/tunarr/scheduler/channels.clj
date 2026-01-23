(ns tunarr.scheduler.channels
  "Universal channel specification and schema.
  
  This namespace defines backend-agnostic channel representations that can
  be mapped to specific backend formats (ErsatzTV, Tunarr, etc.)."
  (:require [clojure.spec.alpha :as s]))

;; Basic channel identification
(s/def ::channel-id keyword?)
(s/def ::channel-name string?)
(s/def ::channel-number
  (s/or :integer int?
        :decimal (s/and string? #(re-matches #"\d+\.\d+" %))))
(s/def ::channel-description (s/nilable string?))
(s/def ::channel-logo-url (s/nilable string?))

;; Streaming configuration
(s/def ::streaming-mode
  #{:hls-segmenter :mpeg-ts :hls-direct :mpeg-ts-legacy})

;; Watermark configuration
(s/def ::watermark-enabled boolean?)
(s/def ::watermark-url (s/nilable string?))
(s/def ::watermark-position
  #{:top-left :top-right :bottom-left :bottom-right})
(s/def ::watermark-opacity (s/and number? #(<= 0 % 100)))
(s/def ::watermark-size (s/and int? pos?))

(s/def ::watermark-config
  (s/keys :opt [::watermark-enabled
                ::watermark-url
                ::watermark-position
                ::watermark-opacity
                ::watermark-size]))

;; Audio and subtitle configuration
(s/def ::preferred-audio-language (s/nilable string?))
(s/def ::subtitle-mode #{:none :burned-in :external})

;; Channel grouping
(s/def ::channel-group (s/nilable string?))

;; Backend-specific settings
(s/def ::backend-settings (s/map-of keyword? any?))

;; Generic channel specification
(s/def ::channel-spec
  (s/keys :req [::channel-id ::channel-name ::channel-number]
          :opt [::channel-description
                ::channel-logo-url
                ::streaming-mode
                ::watermark-config
                ::preferred-audio-language
                ::subtitle-mode
                ::channel-group
                ::backend-settings]))

;; Backend-specific extensions for ErsatzTV
(s/def ::ersatz-streaming-mode string?)
(s/def ::ersatz-watermark-config map?)
(s/def ::ersatz-fallback-filler (s/nilable string?))
(s/def ::ersatz-playback-order string?)

(s/def ::ersatztv-channel-spec
  (s/merge ::channel-spec
           (s/keys :opt [::ersatz-streaming-mode
                         ::ersatz-watermark-config
                         ::ersatz-fallback-filler
                         ::ersatz-playback-order])))

;; Backend-specific extensions for Tunarr
(s/def ::tunarr-on-demand-mode boolean?)
(s/def ::tunarr-stealth-mode boolean?)
(s/def ::tunarr-offline-mode #{:pic :clip})

(s/def ::tunarr-channel-spec
  (s/merge ::channel-spec
           (s/keys :opt [::tunarr-on-demand-mode
                         ::tunarr-stealth-mode
                         ::tunarr-offline-mode])))

;; Helper functions
(defn valid-channel?
  "Check if a channel spec is valid according to the schema.
  
  Args:
    channel-spec - Channel specification map
  
  Returns:
    true if valid, false otherwise"
  [channel-spec]
  (s/valid? ::channel-spec channel-spec))

(defn explain-channel
  "Get detailed explanation of why a channel spec is invalid.
  
  Args:
    channel-spec - Channel specification map
  
  Returns:
    Spec explanation data or nil if valid"
  [channel-spec]
  (when-not (valid-channel? channel-spec)
    (s/explain-data ::channel-spec channel-spec)))

(defn channel-number->string
  "Convert a channel number to its string representation.
  
  Args:
    channel-number - Integer or decimal string
  
  Returns:
    String representation of the channel number"
  [channel-number]
  (cond
    (int? channel-number) (str channel-number)
    (string? channel-number) channel-number
    :else (str channel-number)))

(defn channel-number->int
  "Convert a channel number to its integer representation.
  
  Args:
    channel-number - Integer or decimal string
  
  Returns:
    Integer representation (floor for decimals)"
  [channel-number]
  (cond
    (int? channel-number) channel-number
    (string? channel-number)
    (if (re-matches #"\d+\.\d+" channel-number)
      (int (Double/parseDouble channel-number))
      (Integer/parseInt channel-number))
    :else (int channel-number)))
