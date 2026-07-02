(ns tunarr.scheduler.bumpers
  "Bumper generation service.

   Orchestrates the full bumper creation pipeline:
   1. Call Tunabrain to generate a channel-appropriate image (prompt + generation)
   2. Select a matching music track from the CC0 library
   3. Compose image + audio into a short MP4 with subtle motion using ffmpeg
   4. Upload the result to shared storage
   5. Register the bumper as a media item in Pseudovision

   Generated bumpers are organized into duration buckets:
     - 5 seconds  → tiny cracks between content
     - 10 seconds → standard transitions
     - 15 seconds → slightly longer gaps

   Bumpers are tagged with channel and theme metadata so Pseudovision can
   select the right one at playout time."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [taoensso.timbre :as log]
             [tunarr.scheduler.tunabrain :as tunabrain]
             [tunarr.scheduler.backends.jellyfin.client :as jellyfin]
             [tunarr.scheduler.backends.pseudovision.collections :as pv])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def default-durations [5 10 15])
(def max-bumpers-per-channel 12)
(def ^:private music-library-dir
  "Directory containing CC0 music organized by mood subdirectories.
   Set via :bumpers :music-library-dir in config."
  "/net/projects/niten/tunarr-scheduler/tools/bumper-music")

;; ---------------------------------------------------------------------------
;; Music selection
;; ---------------------------------------------------------------------------

(defn discover-music-tracks
  "Find all audio files under the music library directory."
  [library-dir]
  (let [dir (io/file library-dir)]
    (when (.exists dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (filter #(re-find #"\.(mp3|wav|flac|ogg|m4a|aac)$" (.getName %)))
           (map #(.getAbsolutePath %))
           vec))))

(defn- select-music-track
  "Pick a random music track from the library.
   In the future this could be mood-matched; for now it's random."
  [library-dir]
  (let [tracks (discover-music-tracks library-dir)]
    (when (seq tracks)
      (rand-nth tracks))))

;; ---------------------------------------------------------------------------
;; ffmpeg composition
;; ---------------------------------------------------------------------------

(defn ffmpeg-available?
  "Check if ffmpeg is installed and accessible."
  []
  (try
    (= 0 (:exit (shell/sh "ffmpeg" "-version")))
    (catch Exception _
      false)))

(defn- build-video-filter
  "Build the ffmpeg video filter string for a bumper of `target-secs` duration."
  [target-secs]
  (let [fade-in 1.0
        fade-out 1.5]
    (format "loop=loop=-1:size=1:start=0,zoompan=z='min(zoom+0.0015,1.15)':d=%s:s=1920x1080:fps=30,fade=t=in:st=0:d=%s,fade=t=out:st=%s:d=%s"
            (int (* target-secs 30))
            fade-in
            (- target-secs fade-out)
            fade-out)))

(defn- compose-bumper!
  "Use ffmpeg to turn an image + audio into a bumper MP4.

   Effects applied:
   - Slow Ken Burns zoom/pan on the image
   - Fade-in / fade-out on both video and audio
   - Exact target duration (loops or trims audio as needed)

   Returns the path to the generated MP4."
  [image-path music-path dest-path target-secs]
  (when-not (ffmpeg-available?)
    (throw (ex-info "ffmpeg not found — install it to generate bumpers"
                    {:hint "nix-shell -p ffmpeg"})))

  (io/make-parents dest-path)

  (let [vf (build-video-filter target-secs)
        fade-in  1.0
        fade-out 1.5
        af (format "afade=t=in:st=0:d=%s,afade=t=out:st=%s:d=%s"
                   fade-in
                   (- target-secs fade-out)
                   fade-out)

        cmd ["ffmpeg" "-y"
             "-loop" "1" "-i" image-path
             "-i" music-path
             "-vf" vf
             "-af" af
             "-c:v" "libx264" "-pix_fmt" "yuv420p"
             "-c:a" "aac" "-b:a" "192k"
             "-ar" "48000" "-ac" "2"
             "-shortest"
             "-t" (str target-secs)
             dest-path]

        result (apply shell/sh cmd)]

    (if (= 0 (:exit result))
      (do
        (log/info "Composed bumper" {:dest dest-path :duration target-secs})
        dest-path)
      (throw (ex-info "ffmpeg bumper composition failed"
                      {:cmd cmd
                       :exit (:exit result)
                       :stderr (:err result)})))))

(defn- compose-silent-bumper!
  "Use ffmpeg to turn an image into a silent bumper MP4.

   Effects applied:
   - Slow Ken Burns zoom/pan on the image
   - Fade-in / fade-out on the video
   - Exact target duration

   Returns the path to the generated MP4."
  [image-path dest-path target-secs]
  (when-not (ffmpeg-available?)
    (throw (ex-info "ffmpeg not found — install it to generate bumpers"
                    {:hint "nix-shell -p ffmpeg"})))

  (io/make-parents dest-path)

  (let [vf (build-video-filter target-secs)
        cmd ["ffmpeg" "-y"
             "-loop" "1" "-i" image-path
             "-vf" vf
             "-c:v" "libx264" "-pix_fmt" "yuv420p"
             "-t" (str target-secs)
             dest-path]

        result (apply shell/sh cmd)]

    (if (= 0 (:exit result))
      (do
        (log/info "Composed silent bumper" {:dest dest-path :duration target-secs})
        dest-path)
      (throw (ex-info "ffmpeg silent bumper composition failed"
                      {:cmd cmd
                       :exit (:exit result)
                       :stderr (:err result)})))))

;; ---------------------------------------------------------------------------
;; Image helpers
;; ---------------------------------------------------------------------------

(defn- decode-base64-image
  "Decode a base64 string and write it to `dest-path` as a PNG."
  [^String b64 dest-path]
  (let [bytes (.decode (Base64/getDecoder) b64)
        dest-file (io/file dest-path)
        parent (.getParentFile dest-file)]
    (when parent
      (let [ok (.mkdirs parent)]
        (log/info "Ensured parent directory" {:path (.getAbsolutePath parent) :created ok :exists (.exists parent)})))
    (with-open [out (io/output-stream dest-file)]
      (.write out bytes))
    (log/info "Saved base64 image" {:path dest-path :bytes (alength bytes)})
    dest-path))

;; ---------------------------------------------------------------------------
;; Generation pipeline
;; ---------------------------------------------------------------------------

(defn generate-bumper!
  "Generate a single bumper for a channel.

   Args:
     tunabrain-client — Tunabrain client (from tunabrain/create!)
     channel-key      — Keyword like :enigma, :hua
     channel-spec     — Map with :name :description
     target-secs      — 5, 10, or 15
     dest-dir         — Base directory; files written to {dest-dir}/{channel-key}/
     opts             — Optional {:music-library-dir ... :theme ...}

   Returns:
     {:mp4-path string :image-path string :prompt string :duration int
      :channel string :mp4-filename string}"
  [tunabrain-client channel-key channel-spec target-secs dest-dir & [opts]]
  (let [channel-name (:name channel-spec)
        channel-desc (:description channel-spec)
        theme (:theme opts)
        music-dir (or (:music-library-dir opts) music-library-dir)

        ;; Use a channel-specific sub-directory so Jellyfin/PV can organise by channel
        channel-dir (io/file dest-dir (name channel-key))
        _ (.mkdirs channel-dir)

        ;; 1. Generate image via Tunabrain
        {:keys [title script image-base64]}
        (tunabrain/generate-bumper! tunabrain-client channel-name channel-desc
                                    target-secs :theme theme)

        ;; 2. Save image
        ts (System/currentTimeMillis)
        image-filename (format "%s-%s-%ds-image.png"
                               (name channel-key)
                               ts
                               target-secs)
        image-path (str (io/file channel-dir image-filename))
        _ (when image-base64
            (decode-base64-image image-base64 image-path))

        ;; 3. Select music
        music-track (select-music-track music-dir)
        _ (when-not music-track
            (log/warn "No music tracks found in library" {:dir music-dir}))

        ;; 4. Compose
        mp4-filename (format "%s-%s-%ds-bumper.mp4"
                             (name channel-key)
                             ts
                             target-secs)
        mp4-path (str (io/file channel-dir mp4-filename))
        final-path (if music-track
                     (compose-bumper! image-path music-track mp4-path target-secs)
                     (do (log/warn "No music track available, generating silent bumper")
                         (compose-silent-bumper! image-path mp4-path target-secs)))]

    {:mp4-path final-path
     :image-path image-path
     :prompt script
     :duration target-secs
     :channel (name channel-key)
     :mp4-filename mp4-filename}))

;; ---------------------------------------------------------------------------
;; Batch generation
;; ---------------------------------------------------------------------------

(defn generate-channel-bumpers!
  "Generate a full pool of bumpers for one channel.

   Produces max-bumpers-per-channel bumpers spread across duration buckets."
  [tunabrain-client channel-key channel-spec dest-dir & [opts]]
  (let [durations (or (:durations opts) default-durations)
        count (or (:count opts) max-bumpers-per-channel)
        buckets (cycle durations)]
    (log/info "Generating bumper pool" {:channel channel-key :count count :durations durations})
    (doall
     (map-indexed
      (fn [idx target-secs]
        (try
          (generate-bumper! tunabrain-client channel-key channel-spec
                            target-secs dest-dir opts)
          (catch Exception e
            (log/error e "Bumper generation failed" {:channel channel-key :idx idx})
            nil)))
      (take count buckets)))))

;; ---------------------------------------------------------------------------
;; Jellyfin + Pseudovision orchestration
;; ---------------------------------------------------------------------------

(defn- poll-jellyfin-item
  "Poll Jellyfin for an item in `library-name` matching `item-name`.
   Retries every `sleep-ms` up to `max-attempts` times.
   Returns the item map with :Id, :Name, etc., or nil if not found."
  [jellyfin-client library-name item-name & {:keys [max-attempts sleep-ms]
                                            :or {max-attempts 30 sleep-ms 2000}}]
  (let [jf (:base-url jellyfin-client)
        key (:api-key jellyfin-client)]
    (when (and jf key)
      (if-let [library (jellyfin/find-library-by-name jf key library-name)]
        (loop [attempt 1]
          (if (> attempt max-attempts)
            (do (log/warn "Jellyfin item not found after max attempts" {:name item-name :attempts max-attempts})
                nil)
            (if-let [item (jellyfin/find-item-by-name jf key (:ItemId library) item-name)]
              (do (log/info "Found Jellyfin item" {:name item-name :id (:Id item) :attempt attempt})
                  item)
              (do (log/debug "Jellyfin item not yet indexed, retrying..." {:attempt attempt})
                  (Thread/sleep sleep-ms)
                  (recur (inc attempt))))))
        (do (log/warn "Jellyfin library not found for polling" {:name library-name})
            nil)))))

(defn- try-register-bumper!
  "Attempt to register a bumper in Pseudovision after Jellyfin has indexed it.
   Returns {:registered boolean :jellyfin-id (or nil string)}."
  [pv-base-url channel-name jellyfin-item]
  (if-let [jf-id (:Id jellyfin-item)]
    (try
      (if (pv/register-bumper! pv-base-url channel-name jf-id)
        (do (log/info "Bumper registered in Pseudovision" {:channel channel-name :jellyfin-id jf-id})
            {:registered true :jellyfin-id jf-id})
        (do (log/warn "Bumper not yet available in Pseudovision, will retry on next sync"
                       {:channel channel-name :jellyfin-id jf-id})
            {:registered false :jellyfin-id jf-id}))
      (catch Exception e
        (log/error e "PV registration failed" {:channel channel-name :jellyfin-id jf-id})
        {:registered false :jellyfin-id jf-id :error (ex-message e)}))
    {:registered false :jellyfin-id nil}))

(defn register-generated-bumper!
  "Orchestrate Jellyfin scan + PV registration for a single bumper.
   **Deprecated in batch workflows** — prefer `register-bumper-batch!` to avoid
   redundant library scans.

   Args:
     jellyfin-client  — Map from jellyfin/create! (or nil)
     pv-base-url      — Pseudovision base URL string (or nil)
     channel-name     — Human-readable channel name
     bumper-info      — Map returned by generate-bumper! (must include :mp4-filename)
     jellyfin-library — Name of the Jellyfin library that scans the bumper path

   Returns:
     {:registered boolean :jellyfin-id (or nil) :mp4-path string}"
  [jellyfin-client pv-base-url channel-name bumper-info jellyfin-library]
  (let [mp4-path (:mp4-path bumper-info)
        mp4-filename (:mp4-filename bumper-info)]
    (cond
      (not jellyfin-client)
      (do (log/info "Jellyfin client not configured, skipping registration" {:path mp4-path})
          {:registered false :jellyfin-id nil :mp4-path mp4-path})

      (not pv-base-url)
      (do (log/info "Pseudovision URL not configured, skipping registration" {:path mp4-path})
          {:registered false :jellyfin-id nil :mp4-path mp4-path})

      :else
      (do
        ;; 1. Trigger Jellyfin library scan
        (jellyfin/trigger-library-scan (:base-url jellyfin-client)
                                        (:api-key jellyfin-client)
                                        jellyfin-library)
        ;; 2. Poll for the newly indexed item
        (if-let [jf-item (poll-jellyfin-item jellyfin-client jellyfin-library mp4-filename)]
          ;; 3. Register in PV
          (merge (try-register-bumper! pv-base-url channel-name jf-item)
                 {:mp4-path mp4-path})
          (do (log/warn "Could not find bumper in Jellyfin, skipping PV registration"
                        {:filename mp4-filename :library jellyfin-library})
              {:registered false :jellyfin-id nil :mp4-path mp4-path}))))))

(defn register-bumper-batch!
  "Register a batch of generated bumpers efficiently.
   Triggers a single Jellyfin library scan, then polls for all items.

   Args:
     jellyfin-client  — Map from jellyfin/create! (or nil)
     pv-base-url      — Pseudovision base URL string (or nil)
     channel-name     — Human-readable channel name
     bumper-infos     — Seq of maps returned by generate-bumper!
     jellyfin-library — Name of the Jellyfin library that scans the bumper path

   Returns:
     Seq of {:registered boolean :jellyfin-id (or nil) :mp4-path string}"
  [jellyfin-client pv-base-url channel-name bumper-infos jellyfin-library]
  (cond
    (not jellyfin-client)
    (do (log/info "Jellyfin client not configured, skipping batch registration"
                  {:count (count bumper-infos)})
        (mapv #(assoc % :registered false :jellyfin-id nil) bumper-infos))

    (not pv-base-url)
    (do (log/info "Pseudovision URL not configured, skipping batch registration"
                  {:count (count bumper-infos)})
        (mapv #(assoc % :registered false :jellyfin-id nil) bumper-infos))

    :else
    (do
      ;; 1. Trigger a single scan for the whole batch
      (jellyfin/trigger-library-scan (:base-url jellyfin-client)
                                      (:api-key jellyfin-client)
                                      jellyfin-library)
      ;; 2. Poll + register each bumper
      (mapv (fn [bumper]
              (let [mp4-filename (:mp4-filename bumper)]
                (if-let [jf-item (poll-jellyfin-item jellyfin-client
                                                      jellyfin-library
                                                      mp4-filename
                                                      :max-attempts 30
                                                      :sleep-ms 2000)]
                  (merge (try-register-bumper! pv-base-url channel-name jf-item)
                         {:mp4-path (:mp4-path bumper)})
                  (do (log/warn "Could not find bumper in Jellyfin"
                                {:filename mp4-filename :library jellyfin-library})
                      {:registered false :jellyfin-id nil :mp4-path (:mp4-path bumper)}))))
            bumper-infos))))

;; ---------------------------------------------------------------------------
;; Service lifecycle
;; ---------------------------------------------------------------------------

(defn- default-output-dir
  "Return the default bumper output directory.
   Prefers BUMPER_OUTPUT_DIR env var, then falls back to /data/media/bumpers
   (the arr-data mount shared with Jellyfin)."
  []
  (or (System/getenv "BUMPER_OUTPUT_DIR")
      "/data/media/bumpers"))

(defn create-service
  "Create the bumper generation service from system config.

   Config keys under :bumpers:
     :tunabrain         — Tunabrain client (required)
     :music-library-dir — Path to CC0 music library
     :output-dir        — Where to write generated MP4s
     :jellyfin          — Jellyfin client config map (optional)
     :pseudovision-url  — Pseudovision base URL (optional)"
  [{:keys [tunabrain music-library-dir output-dir jellyfin pseudovision-url]}]
  (log/info "Initialising bumper service")
  (let [out-dir (or output-dir (default-output-dir))
        jf-client (when jellyfin (jellyfin/create! jellyfin))]
    (let [f (io/file out-dir)
          ok (.mkdirs f)]
      (log/info "Ensured bumper output directory"
                {:path out-dir :created ok :exists (.exists f)}))
    {:tunabrain tunabrain
     :music-library-dir (or music-library-dir
                             (str (System/getProperty "user.dir") "/tools/bumper-music"))
     :output-dir   out-dir
     :jellyfin     jf-client
     :pseudovision-url pseudovision-url}))

(defn close!
  "No-op shutdown — bumper service is stateless."
  [_]
  (log/info "Closing bumper service"))
