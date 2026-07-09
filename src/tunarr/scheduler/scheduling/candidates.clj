(ns tunarr.scheduler.scheduling.candidates
  "Precomputes duration-feasible slot-tiling candidates for one daypart block,
   from the catalog's per-tag runtime histogram (CatalogProfile
   tag_runtime_histograms). Pure, deterministic, no I/O, no LLM — the
   arithmetic the LLM should never have to do freehand when filling a daypart
   with strips.

   See DURATION_AWARE_SCHEDULING.md §4.2 for the design and §4.3 for how this
   fits into the split-round-trip proposal flow (Pass A returns real daypart
   bounds; this runs against those bounds; the result is handed to Tunabrain's
   propose-strip-fill as a menu it's guided to prefer over inventing lengths).

   v1 generates HOMOGENEOUS candidates only — one candidate per (tag,
   populated bucket), tiling the whole block back-to-back at that bucket's
   representative length. Mixed-category layouts (e.g. a movie followed by
   several half-hour sitcoms) are a documented future extension; the
   DaypartCandidate shape (a vector of CandidateSlots) already supports them
   without a contract change — see DURATION_AWARE_SCHEDULING.md §4.2."
  (:require [clojure.string :as str]))

(def ^:const max-candidates-per-block
  "Caps the candidate menu handed to Pass B, same prompt-budget discipline as
   quarterly_grid.py's summarize_catalog_profile show sampling."
  8)

(def ^:const default-open-bucket-width-minutes
  "Assumed width for an open-ended top bucket (nil max_minutes) when computing
   its representative midpoint — matches the 15-minute bucket scheme
   Pseudovision's histogram uses (list-tag-runtime-histogram)."
  15)

(defn- parse-mins [hhmm]
  (let [[h m] (map parse-long (str/split hhmm #":"))]
    (+ (* 60 h) m)))

(defn- block-duration-minutes
  "A daypart block's own wall-clock length in minutes. `end <= start` wraps
   past midnight, same convention as the grid strips/overrides use throughout."
  [block]
  (let [s (parse-mins (:start block)) e (parse-mins (:end block))]
    (if (> e s) (- e s) (+ (- 1440 s) e))))

(defn- bucket-width [bucket]
  (if-let [mx (:max_minutes bucket)]
    (max 1 (- mx (:min_minutes bucket)))
    default-open-bucket-width-minutes))

(defn- bucket-midpoint [bucket]
  (+ (:min_minutes bucket) (/ (bucket-width bucket) 2.0)))

(defn- bare-tag
  "Strips a leading 'genre:' (or any single 'word:' dimension prefix) from a
   tag for display/matching against a block's bare genre_focus entries, e.g.
   'genre:movie' -> 'movie'. Mirrors the bare-or-prefixed duality
   feasibility.clj's tag-runtime-histogram and daily-slots.clj's
   resolve-by-category already handle."
  [tag]
  (str/replace tag #"(?i)^[a-z-]+:" ""))

(defn- relevant?
  "Whether `tag` matches the block's genre_focus (case-insensitive, prefix-
   agnostic). An empty genre_focus matches everything — a block with no
   stated focus shouldn't have its candidate pool arbitrarily narrowed."
  [tag genre-focus]
  (or (empty? genre-focus)
      (let [bare (str/lower-case (bare-tag tag))]
        (some #(= bare (str/lower-case %)) genre-focus))))

(defn- homogeneous-candidate
  "One candidate: tile `block-duration` minutes back-to-back with slots sized
   to `bucket`'s representative (midpoint) length, all drawing from `tag`.
   The slot count is chosen so the slots' total exactly covers the block
   (evenly dividing block-duration), not the bucket width itself — so the
   tiling is always exact and contiguous, while still landing close to a
   length real inventory is known to have."
  [block-duration tag bucket]
  (let [target (bucket-midpoint bucket)
        n      (max 1 (Math/round (double (/ block-duration target))))
        slot-duration (Math/round (/ block-duration (double n)))]
    {:layout_id (str (bare-tag (str/replace tag #"[^a-zA-Z0-9:]" "-")) "-" (:label bucket))
     :weight    (double (:item_count bucket))
     :slots     (vec (repeat n {:duration_minutes slot-duration
                                :category         (bare-tag tag)
                                :available_count  (:item_count bucket)}))}))

(defn propose-daypart-candidates
  "Duration-feasible tiling candidates for `block` (a DaypartBlock-shaped map
   with :start/:end/:genre_focus), built from `catalog-profile`'s
   tag_runtime_histograms. Prefers tags matching the block's genre_focus;
   falls back to every tag with histogram data if none match (a block should
   never receive an empty menu just because its focus didn't line up with
   naming). Capped to `max-candidates-per-block`, highest-weight (best-stocked)
   first.

   Returns [] when the profile carries no tag_runtime_histograms at all (an
   older Pseudovision build) — callers should treat that the same as 'no
   candidates available' and fall back to unconstrained strip-fill, not an
   error."
  [catalog-profile block]
  (let [duration    (block-duration-minutes block)
        histograms  (:tag_runtime_histograms catalog-profile)
        genre-focus (:genre_focus block)
        wanted      (filterv #(relevant? (:tag %) genre-focus) histograms)
        pool        (if (seq wanted) wanted histograms)]
    (->> pool
         (mapcat (fn [{:keys [tag buckets]}]
                   (for [bucket buckets
                         :when (pos? (:item_count bucket))]
                     (homogeneous-candidate duration tag bucket))))
         (sort-by :weight >)
         (take max-candidates-per-block)
         vec)))
