(ns tunarr.scheduler.bumpers
  "Bumper generation service.

   Orchestrates the full bumper creation pipeline:
   1. Call Tunabrain to generate a channel-appropriate image (prompt + generation)
   2. Select a matching music track from the CC0 library
   3. Compose image + audio into a short MP4 with subtle motion using ffmpeg
   4. Write the result to the shared Grout staging mount
   5. Upload it to Grout via the intake API, tagged so Pseudovision can find it

   Generated bumpers are organized into duration buckets:
     - 5 seconds  → tiny cracks between content
     - 10 seconds → standard transitions
     - 15 seconds → slightly longer gaps

   Bumpers are tagged with channel/kind/theme metadata at intake so Pseudovision
   can select the right one at playout time. Grout owns the stored bytes (it
   normalises + content-addresses on intake), so the staging file is a transient
   hand-off, not the system of record — replacing the old Jellyfin scan → poll →
   PV-collection registration dance."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [taoensso.timbre :as log]
            [tunarr.scheduler.tunabrain :as tunabrain]
            [tunarr.scheduler.backends.grout.client :as grout])
  (:import [java.util Base64]))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def default-durations [5 10 15])
(def max-bumpers-per-channel 12)
(def ^:private default-music-dir
  "Fallback directory containing CC0 music organized by mood subdirectories.
   Lives on the arr-data mount alongside the generated bumpers. Override via
   the BUMPER_MUSIC_DIR env var or :bumpers :music-library-dir in config."
  "/data/media/bumper-music")

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

        ;; -stream_loop -1 repeats the audio input so a track shorter than the
        ;; target still fills the whole bumper; -t is the single duration
        ;; authority. We deliberately drop -shortest: with a looped (infinite)
        ;; image and looped audio, -shortest would otherwise let a short track
        ;; truncate the bumper below target-secs.
        cmd ["ffmpeg" "-y"
             "-loop" "1" "-i" image-path
             "-stream_loop" "-1" "-i" music-path
             "-vf" vf
             "-af" af
             "-c:v" "libx264" "-pix_fmt" "yuv420p"
             "-c:a" "aac" "-b:a" "192k"
             "-ar" "48000" "-ac" "2"
             ;; +faststart moves the moov atom up front so the file arrives
             ;; stream-ready. Grout re-adds this on intake regardless, but doing
             ;; it here lets it skip a re-mux (GROUT.md §8).
             "-movflags" "+faststart"
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
             ;; +faststart: stream-ready moov placement (see compose-bumper!).
             "-movflags" "+faststart"
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
     {:mp4-path string :image-path string :title string :prompt string
      :duration int :channel string :theme (or keyword nil)
      :mp4-filename string}"
  [tunabrain-client channel-key channel-spec target-secs dest-dir & [opts]]
  (let [channel-name (:name channel-spec)
        channel-desc (:description channel-spec)
        theme (:theme opts)
        music-dir (or (:music-library-dir opts) default-music-dir)

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
     :title title
     :prompt script
     :duration target-secs
     :channel (name channel-key)
     :theme theme
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
;; Grout upload
;; ---------------------------------------------------------------------------
;;
;; Bumpers land in Grout, the filler-content store. We hand Grout the staging
;; path and it hashes → ffprobes → normalises → content-addresses → indexes,
;; returning the stored Media. Intake is idempotent by source hash, so re-runs
;; are safe and the 201/200 response *is* the confirmation — no scan-polling or
;; filename matching. Duration lives on the Media as ffprobed `duration-ms`, so
;; Pseudovision queries by min_ms/max_ms rather than a duration tag.

(defn- bumper->tags
  "Build the intake tag list for a bumper. `kind` and `channel` are dedicated
   intake fields, not tags; the only content signal we have pre-enrichment is
   the theme (Grout's Tunabrain pass fills in the rest later)."
  [{:keys [theme]}]
  (into [] (comp (remove nil?) (map name)) [theme]))

(defn- cleanup-staging-file!
  "Best-effort removal of a staging file once Grout owns the bytes. Grout stores
   at its own content-addressed path, so the staging copy is disposable; failing
   to delete it is not fatal (worst case a harmless re-upload, deduped by hash)."
  [path]
  (try
    (when (and path (.exists (io/file path)))
      (.delete (io/file path))
      (log/debug "Removed staging bumper after Grout intake" {:path path}))
    (catch Exception e
      (log/warn "Failed to remove staging bumper" {:path path :error (ex-message e)}))))

(defn upload-bumper!
  "Upload a single generated bumper to Grout.

   Args:
     grout-client — Map from grout/create! (or nil to skip)
     channel-name — Channel slug for the Grout `channel` field
     bumper-info  — Map returned by generate-bumper!

   Returns {:uploaded boolean :grout-id (or nil) :created? boolean
            :mp4-path string [:error string]}."
  [grout-client channel-name bumper-info]
  (let [mp4-path (:mp4-path bumper-info)]
    (if-not grout-client
      (do (log/info "Grout client not configured, skipping upload" {:path mp4-path})
          {:uploaded false :grout-id nil :mp4-path mp4-path})
      (try
        (let [{:keys [created? media]}
              (grout/intake! grout-client
                             {:path        mp4-path
                              :kind        "bumper"
                              :channel     channel-name
                              :tags        (bumper->tags bumper-info)
                              :source      "tunarr-bumper"
                              :name        (:title bumper-info)
                              :description (:prompt bumper-info)})]
          (cleanup-staging-file! mp4-path)
          (log/info "Bumper uploaded to Grout"
                    {:channel channel-name :grout-id (:id media) :created? created?})
          {:uploaded true :grout-id (:id media) :created? created? :mp4-path mp4-path})
        (catch Exception e
          (log/error e "Grout upload failed" {:channel channel-name :path mp4-path})
          {:uploaded false :grout-id nil :mp4-path mp4-path :error (ex-message e)})))))

(defn upload-bumper-batch!
  "Upload a batch of generated bumpers to Grout. Each intake is independent and
   idempotent by hash, so a single failure doesn't abort the rest.

   Args:
     grout-client — Map from grout/create! (or nil to skip)
     channel-name — Channel slug for the Grout `channel` field
     bumper-infos — Seq of maps returned by generate-bumper!

   Returns a vector of per-bumper result maps (see upload-bumper!)."
  [grout-client channel-name bumper-infos]
  (if-not grout-client
    (do (log/info "Grout client not configured, skipping batch upload"
                  {:count (count bumper-infos)})
        (mapv #(hash-map :uploaded false :grout-id nil :mp4-path (:mp4-path %))
              bumper-infos))
    (mapv #(upload-bumper! grout-client channel-name %) bumper-infos)))

;; ---------------------------------------------------------------------------
;; Service lifecycle
;; ---------------------------------------------------------------------------

(defn- default-output-dir
  "Return the default bumper staging directory.
   Prefers BUMPER_OUTPUT_DIR, then GROUT_STAGING_DIR, then a staging subdir under
   the shared Grout mount. Grout requires intake paths to live under
   GROUT_MEDIA_DIR, and it takes ownership of the bytes on intake, so this is a
   transient staging area rather than the store of record."
  []
  (or (System/getenv "BUMPER_OUTPUT_DIR")
      (System/getenv "GROUT_STAGING_DIR")
      "/data/media/grout/staging"))

(defn create-service
  "Create the bumper generation service from system config.

   Config keys under :bumpers:
     :tunabrain         — Tunabrain client (required)
     :music-library-dir — Path to CC0 music library
     :output-dir        — Grout staging dir for generated MP4s
     :grout             — Grout client config map with :base-url (optional; when
                          absent, generation still works but upload is skipped)"
  [{:keys [tunabrain music-library-dir output-dir grout]}]
  (log/info "Initialising bumper service")
  (let [out-dir (or output-dir (default-output-dir))
        grout-client (when grout (grout/create! grout))]
    (let [f (io/file out-dir)
          ok (.mkdirs f)]
      (log/info "Ensured bumper staging directory"
                {:path out-dir :created ok :exists (.exists f)}))
    {:tunabrain tunabrain
     :music-library-dir (or music-library-dir default-music-dir)
     :output-dir   out-dir
     :grout        grout-client}))

(defn close!
  "No-op shutdown — bumper service is stateless."
  [_]
  (log/info "Closing bumper service"))
