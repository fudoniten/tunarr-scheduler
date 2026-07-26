(ns tunarr.scheduler.http.api.bumpers
  "HTTP handlers for bumper generation operations."
  (:require [taoensso.timbre :as log]
            [camel-snake-kebab.core :as csk]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [tunarr.scheduler.jobs.runner :as jobs]
            [tunarr.scheduler.bumpers :as bumpers]))

(defn- check-prerequisites
  "Verify that bumper generation can proceed. Returns nil on success,
   or an error map {:error string} on failure."
  [{:keys [music-library-dir]}]
  (cond
    (not (bumpers/ffmpeg-available?))
    {:error "ffmpeg is not available. Install ffmpeg to generate bumpers."
     :hint "nix-shell -p ffmpeg"}

    :else nil))

(defn- grout-channel-tag
  "The Grout `channel` value to tag a channel's bumper uploads with —
   NOT the `?channel=` config-key slug (e.g. :enigma) this handler is keyed
   by. Pseudovision resolves its own Grout `channel` query filter by
   kebab-casing the PV channel's `:name` column
   (pseudovision.media.grout-source/channel-tag), and that `:name` is set
   from this same channel-spec's `:name` by the channel sync
   (tunarr.scheduler.channels.sync/channel-spec->pseudovision) — e.g.
   \"Enigma TV\" for the :enigma config key. Tagging uploads with the raw
   config-key slug instead (\"enigma\") would silently never match PV's
   query (\"enigma-tv\"), so this must kebab-case the same `:name` field PV
   derives its filter from, not the config key."
  [channel-spec]
  (let [nm (:name channel-spec)]
    (when-not (str/blank? nm) (csk/->kebab-case nm))))

(defn generate-bumpers-handler
  "POST /api/bumpers/generate

   Trigger async bumper generation for a channel.

   Query params:
     :channel    - Channel key (e.g. 'enigma', 'hua')
     :count      - Number of bumpers to generate (default: 12)
     :durations  - Comma-separated durations in seconds (default: '5,10,15')"
  [{:keys [job-runner bumpers channels]}]
  (fn [{{{:keys [channel count durations]} :query} :parameters}]
    (let [channel-key (keyword channel)
          channel-spec (get channels channel-key)]
      (cond
        (not channel-spec)
        {:status 400
         :body {:error (format "Unknown channel: %s. Valid channels: %s"
                               channel
                               (str/join ", " (map name (keys channels))))}}

        (not bumpers)
        {:status 503
         :body {:error "Bumper service is not configured."}}

        :else
        (if-let [prereq-error (check-prerequisites bumpers)]
          {:status 503 :body prereq-error}
          (let [durations-list (if durations
                                 (map #(Integer/parseInt %)
                                      (str/split durations #","))
                                 bumpers/default-durations)
                target-count (or (when count (Integer/parseInt count))
                                  bumpers/max-bumpers-per-channel)
                job (jobs/submit! job-runner
                                  {:type :generate-bumpers
                                   :metadata {:channel channel-key
                                              :count target-count
                                              :durations durations-list}}
                                  (fn [report-progress]
                                    (log/info "Starting bumper generation"
                                              {:channel channel-key
                                               :count target-count
                                               :durations durations-list})
                                    (let [generated (bumpers/generate-channel-bumpers!
                                                     (:tunabrain bumpers)
                                                     channel-key
                                                     channel-spec
                                                     (:output-dir bumpers)
                                                     {:music-library-dir (:music-library-dir bumpers)
                                                      :durations durations-list
                                                      :count target-count})
                                          valid-bumpers (filter some? generated)]
                                      ;; Upload all generated bumpers to Grout (tagged for PV) —
                                      ;; must use the PV-matching kebab-cased display name, not
                                      ;; the raw config-key slug (see grout-channel-tag).
                                      (when (seq valid-bumpers)
                                        (try
                                          (bumpers/upload-bumper-batch!
                                           (:grout bumpers)
                                           (grout-channel-tag channel-spec)
                                           valid-bumpers)
                                          (catch Exception e
                                            (log/error e "Bumper batch upload to Grout failed"
                                                       {:channel channel-key :count (count valid-bumpers)}))))
                                      generated)))]
            {:status 202 :body {:job job}}))))))

(defn list-bumpers-handler
  "GET /api/bumpers

   List generated bumpers from the output directory."
  [{:keys [bumpers]}]
  (fn [_]
    (if-not bumpers
      {:status 503 :body {:error "Bumper service is not configured."}}
      (let [out-dir (:output-dir bumpers)
            files (->> (file-seq (io/file out-dir))
                       (filter #(.isFile %))
                       (filter #(str/ends-with? (.getName %) ".mp4"))
                       (map (fn [f]
                              {:path (.getAbsolutePath f)
                               :name (.getName f)
                               :size (.length f)
                               :modified (.lastModified f)}))
                       (sort-by :modified #(compare %2 %1))
                       (vec))]
        {:status 200 :body {:directory out-dir
                            :count (count files)
                            :files files}}))))
