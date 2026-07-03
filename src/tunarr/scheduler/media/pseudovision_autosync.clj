(ns tunarr.scheduler.media.pseudovision-autosync
  "Event-driven Pseudovision tag/metadata sync.

   Instead of syncing on a cron, we catch catalog changes as they happen and
   push them to Pseudovision. Two pieces cooperate:

   1. A `SyncingCatalog` decorator (see `wrap-catalog`) that delegates every
      `Catalog` protocol call to an inner catalog and, for tag/category
      *mutations*, marks the affected media item dirty.

   2. A background worker (a plain queue + drain thread, mirroring the
      `jobs.throttler` idiom) that coalesces dirty items over a short debounce
      window and syncs each once. Bursts — e.g. an LLM retagging a whole
      library — collapse into a single batched pass rather than hundreds of
      blocking calls.

   Global tag operations (rename/delete/purge across all items) can't name the
   affected items, so they enqueue a full reconcile instead. A low-frequency
   periodic reconcile runs as a backstop for changes that never flowed through
   this process (direct DB edits, an outage that swallowed a batch)."
  (:require [tunarr.scheduler.media.catalog :as catalog]
            [tunarr.scheduler.media.pseudovision-sync :as pv-sync]
            [tunarr.scheduler.backends.pseudovision.client :as pv-client]
            [taoensso.timbre :as log])
  (:import [java.util HashSet]
           [java.util.concurrent LinkedBlockingQueue TimeUnit]))

;; ---------------------------------------------------------------------------
;; Worker
;; ---------------------------------------------------------------------------

(def ^:private reconcile-token ::reconcile-all)

(defn- drain-into!
  "Take the first queued signal (blocking up to `poll-ms` so the loop can
   observe shutdown), then, after a debounce, pull everything else currently
   queued into a deduplicating set. Returns the set, or nil if nothing was
   waiting."
  [^LinkedBlockingQueue queue poll-ms debounce-ms]
  (when-let [first-signal (.poll queue poll-ms TimeUnit/MILLISECONDS)]
    ;; Let a burst accumulate so N rapid edits collapse into one pass.
    (when (pos? debounce-ms) (Thread/sleep debounce-ms))
    (let [batch (HashSet.)]
      (.add batch first-signal)
      (.drainTo queue batch)
      batch)))

(defn- sync-dirty-ids!
  "Sync a set of dirty catalog ids. For small batches, resolve each item's
   Pseudovision id individually (cheap). For large batches, build the id-map
   once and reuse it. Returns the number of items that failed to sync."
  [{:keys [pseudovision catalog bulk-threshold]} ids]
  (let [pv-config (pv-client/get-config pseudovision)]
    (if (> (count ids) bulk-threshold)
      (let [items (keep #(catalog/get-media-by-id catalog %) ids)
            id-map (pv-sync/build-jellyfin-id-map pv-config)]
        (log/info "auto-sync: bulk item sync" {:count (count items)})
        (:failed (pv-sync/sync-items! catalog pv-config id-map items {})))
      (do
        (log/info "auto-sync: syncing dirty items" {:count (count ids)})
        (reduce (fn [failed id]
                  (if-let [item (catalog/get-media-by-id catalog id)]
                    (let [result (pv-sync/sync-item! pv-config catalog item)]
                      (cond-> failed (:error result) inc))
                    failed))
                0
                ids)))))

(defn- reconcile-all!
  "Sync every catalog item to Pseudovision, building the id-map once.
   Returns the number of items that failed to sync."
  [{:keys [pseudovision catalog]}]
  (let [pv-config (pv-client/get-config pseudovision)
        items     (catalog/get-media catalog)
        id-map    (pv-sync/build-jellyfin-id-map pv-config)]
    (log/info "auto-sync: full reconcile" {:items (count items)})
    (:failed (pv-sync/sync-items! catalog pv-config id-map items {}))))

(defn mark-dirty!
  "Enqueue a single media item for sync."
  [{:keys [queue]} media-id]
  (when (and queue media-id)
    (.offer ^LinkedBlockingQueue queue media-id)))

(defn mark-reconcile!
  "Request a full reconcile on the next drain."
  [{:keys [queue]}]
  (when queue
    (.offer ^LinkedBlockingQueue queue reconcile-token)))

(defn- process-batch!
  "Process one coalesced batch. A reconcile signal (from a global tag op or
   the periodic backstop) supersedes individual ids — it already covers them.
   On a failed targeted batch, escalate to a single reconcile after a backoff
   so a transient Pseudovision outage still gets retried without a tight loop."
  [{:keys [backoff-ms] :as worker} ^HashSet batch]
  (try
    (let [reconcile? (.contains batch reconcile-token)
          _          (.remove batch reconcile-token)
          ids        (set batch)]
      (cond
        reconcile?      (reconcile-all! worker)
        (seq ids)       (let [failed (sync-dirty-ids! worker ids)]
                          (when (pos? failed)
                            (log/warn "auto-sync: some items failed; scheduling reconcile"
                                      {:failed failed :backoff-ms backoff-ms})
                            (Thread/sleep (long backoff-ms))
                            (mark-reconcile! worker)))))
    (catch InterruptedException e (throw e))
    (catch Throwable t
      (log/error t "auto-sync: batch failed"))))

(defn- run-worker!
  [{:keys [queue running? poll-ms debounce-ms] :as worker}]
  (log/info "auto-sync worker started")
  (loop []
    (when @running?
      (try
        (when-let [batch (drain-into! queue poll-ms debounce-ms)]
          (process-batch! worker batch))
        (catch InterruptedException _ (reset! running? false))
        (catch Throwable t (log/error t "auto-sync worker loop error")))
      (recur)))
  (log/info "auto-sync worker stopped"))

(defn- run-reconciler!
  "Periodic backstop: enqueue a full reconcile every `reconcile-ms`.
   Disabled when `reconcile-ms` is nil or non-positive."
  [{:keys [running? reconcile-ms] :as worker}]
  (when (and reconcile-ms (pos? reconcile-ms))
    (log/info "auto-sync reconciler started" {:interval-ms reconcile-ms})
    (try
      (loop []
        ;; Sleep in short slices so shutdown is observed promptly.
        (let [deadline (+ (System/currentTimeMillis) reconcile-ms)]
          (while (and @running? (< (System/currentTimeMillis) deadline))
            (Thread/sleep (min 1000 (max 1 (- deadline (System/currentTimeMillis)))))))
        (when @running?
          (log/info "auto-sync: periodic reconcile tick")
          (mark-reconcile! worker)
          (recur)))
      (catch InterruptedException _ (reset! running? false)))
    (log/info "auto-sync reconciler stopped")))

(defn create
  "Create (but do not start) an auto-sync worker.

   opts:
     :pseudovision   - Pseudovision client (required)
     :catalog        - inner catalog to read from and sync (required)
     :debounce-ms    - coalescing window after the first dirty signal (default 3000)
     :bulk-threshold - batch size above which we build the id-map once (default 50)
     :backoff-ms     - pause before escalating a failed batch to a reconcile (default 30000)
     :reconcile-ms   - periodic full-reconcile interval; nil/0 disables (default 24h)"
  [{:keys [pseudovision catalog debounce-ms bulk-threshold backoff-ms reconcile-ms]
    :or   {debounce-ms 3000 bulk-threshold 50 backoff-ms 30000
           reconcile-ms (* 24 60 60 1000)}}]
  {:queue          (LinkedBlockingQueue.)
   :running?       (atom false)
   :worker-thread  (atom nil)
   :reconcile-thread (atom nil)
   :pseudovision   pseudovision
   :catalog        catalog
   :debounce-ms    debounce-ms
   :poll-ms        500
   :bulk-threshold bulk-threshold
   :backoff-ms     backoff-ms
   :reconcile-ms   reconcile-ms})

(defn start!
  [{:keys [running? worker-thread reconcile-thread] :as worker}]
  (when (compare-and-set! running? false true)
    (reset! worker-thread
            (doto (Thread. #(run-worker! worker) "pv-autosync-worker")
              (.setDaemon true) (.start)))
    (reset! reconcile-thread
            (doto (Thread. #(run-reconciler! worker) "pv-autosync-reconciler")
              (.setDaemon true) (.start))))
  worker)

(defn stop!
  [{:keys [running? worker-thread reconcile-thread]}]
  (reset! running? false)
  (doseq [t-atom [worker-thread reconcile-thread]]
    (when-let [^Thread t @t-atom]
      (.interrupt t)
      (reset! t-atom nil)))
  nil)

;; ---------------------------------------------------------------------------
;; Catalog decorator
;; ---------------------------------------------------------------------------
;;
;; Delegates every Catalog call to `inner`. Item-level tag/category mutations
;; additionally mark the item dirty; global tag/vocabulary operations, which
;; can't enumerate the items they touch, request a reconcile.

(defrecord SyncingCatalog [inner worker]
  catalog/Catalog
  ;; --- reads / non-syncing writes: pure delegation ---
  (add-media! [_ media] (catalog/add-media! inner media))
  (add-media-batch! [_ media-items] (catalog/add-media-batch! inner media-items))
  (get-media [_] (catalog/get-media inner))
  (get-media-by-id [_ media-id] (catalog/get-media-by-id inner media-id))
  (get-media-by-library-id [_ library-id] (catalog/get-media-by-library-id inner library-id))
  (get-media-by-library [_ library] (catalog/get-media-by-library inner library))
  (get-media-by-kind [_ library-name kind] (catalog/get-media-by-kind inner library-name kind))
  (get-filler-items [_ library-name] (catalog/get-filler-items inner library-name))
  (count-media-by-kind [_ library-name] (catalog/count-media-by-kind inner library-name))
  (search-media-by-library-id [_ library-id opts] (catalog/search-media-by-library-id inner library-id opts))
  (get-tags [_] (catalog/get-tags inner))
  (get-channels [_] (catalog/get-channels inner))
  (get-genres [_] (catalog/get-genres inner))
  (get-media-tags [_ media-id] (catalog/get-media-tags inner media-id))
  (update-channels! [_ channels] (catalog/update-channels! inner channels))
  (update-libraries! [_ libraries] (catalog/update-libraries! inner libraries))
  (add-media-channels! [_ media-id channels] (catalog/add-media-channels! inner media-id channels))
  (add-media-genres! [_ media-id genres] (catalog/add-media-genres! inner media-id genres))
  (add-media-taglines! [_ media-id taglines] (catalog/add-media-taglines! inner media-id taglines))
  (get-media-by-channel [_ channel] (catalog/get-media-by-channel inner channel))
  (get-media-by-tag [_ tag] (catalog/get-media-by-tag inner tag))
  (get-media-by-genre [_ genre] (catalog/get-media-by-genre inner genre))
  (get-media-process-timestamps [_ media-id] (catalog/get-media-process-timestamps inner media-id))
  (get-tag-samples [_] (catalog/get-tag-samples inner))
  (update-process-timestamp! [_ media-id process] (catalog/update-process-timestamp! inner media-id process))
  (delete-process-timestamp! [_ media-id process] (catalog/delete-process-timestamp! inner media-id process))
  (delete-library-process-timestamps! [_ library process] (catalog/delete-library-process-timestamps! inner library process))
  (close-catalog! [_] (catalog/close-catalog! inner))
  (get-media-category-values [_ media-id category] (catalog/get-media-category-values inner media-id category))
  (get-media-categories [_ media-id] (catalog/get-media-categories inner media-id))
  (get-episodes-by-series [_ series-id] (catalog/get-episodes-by-series inner series-id))
  (get-episode [_ series-id season-number episode-number] (catalog/get-episode inner series-id season-number episode-number))
  (get-effective-tags [_ media-id] (catalog/get-effective-tags inner media-id))
  (get-effective-categories [_ media-id] (catalog/get-effective-categories inner media-id))
  (get-library-id [_ library] (catalog/get-library-id inner library))
  (enrich-media-with-timestamps [_ media] (catalog/enrich-media-with-timestamps inner media))
  (get-all-dimensions [_] (catalog/get-all-dimensions inner))
  (get-dimension-values [_ dimension] (catalog/get-dimension-values inner dimension))
  (get-media-by-category-value [_ category value] (catalog/get-media-by-category-value inner category value))

  ;; --- item-level tag mutations: sync the touched item ---
  (add-media-tags! [_ media-id tags]
    (let [r (catalog/add-media-tags! inner media-id tags)] (mark-dirty! worker media-id) r))
  (set-media-tags! [_ media-id tags]
    (let [r (catalog/set-media-tags! inner media-id tags)] (mark-dirty! worker media-id) r))
  (delete-media-tags! [_ media-id tags]
    (let [r (catalog/delete-media-tags! inner media-id tags)] (mark-dirty! worker media-id) r))

  ;; --- item-level category mutations: sync the touched item ---
  (add-media-category-value! [_ media-id category value rationale]
    (let [r (catalog/add-media-category-value! inner media-id category value rationale)] (mark-dirty! worker media-id) r))
  (add-media-category-values! [_ media-id category values]
    (let [r (catalog/add-media-category-values! inner media-id category values)] (mark-dirty! worker media-id) r))
  (set-media-category-values! [_ media-id category values]
    (let [r (catalog/set-media-category-values! inner media-id category values)] (mark-dirty! worker media-id) r))
  (delete-media-category-value! [_ media-id category value]
    (let [r (catalog/delete-media-category-value! inner media-id category value)] (mark-dirty! worker media-id) r))
  (delete-media-category-values! [_ media-id category]
    (let [r (catalog/delete-media-category-values! inner media-id category)] (mark-dirty! worker media-id) r))

  ;; --- global tag/vocabulary ops: can't name affected items -> reconcile ---
  (delete-tag! [_ tag]
    (let [r (catalog/delete-tag! inner tag)] (mark-reconcile! worker) r))
  (rename-tag! [_ tag new-tag]
    (let [r (catalog/rename-tag! inner tag new-tag)] (mark-reconcile! worker) r))
  (batch-rename-tags! [_ tag-pairs]
    (let [r (catalog/batch-rename-tags! inner tag-pairs)] (mark-reconcile! worker) r))
  (purge-category-value! [_ category value]
    (let [r (catalog/purge-category-value! inner category value)] (mark-reconcile! worker) r)))

(defn wrap-catalog
  "Wrap `inner` so tag/category mutations enqueue Pseudovision syncs on
   `worker`. Returns `inner` unchanged when `worker` is nil."
  [inner worker]
  (if worker
    (->SyncingCatalog inner worker)
    inner))

(defn wrapped-worker
  "Return the auto-sync worker backing a wrapped catalog, or nil for a plain
   (unwrapped) catalog. Used at shutdown to stop the worker."
  [catalog]
  (when (instance? SyncingCatalog catalog)
    (:worker catalog)))
