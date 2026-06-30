(ns tunarr.scheduler.backends.pseudovision.client
  "Pseudovision IPTV platform HTTP client.

   Provides integration with Pseudovision's native scheduling engine,
   tag management, and streaming capabilities."
  (:require [clj-http.client :as http]
            [cheshire.core :as json]
            [tunarr.scheduler.backends.protocol :as proto]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; HTTP Client Helpers
;; ---------------------------------------------------------------------------

(defn- api-url [config path]
  (str (:base-url config) path))

(defn- request!
  "Make HTTP request to Pseudovision API with error handling."
  [method url opts]
  (try
    (let [response (http/request (merge {:method method
                                         :url url
                                         :accept :json
                                         :as :json
                                         :throw-exceptions false}
                                        opts))]
      (if (<= 200 (:status response) 299)
        (:body response)
        (do
          (log/error "Pseudovision API error"
                     {:status (:status response)
                      :url url
                      :body (:body response)})
          (throw (ex-info "Pseudovision API error"
                          {:status (:status response)
                           :url url
                           :response (:body response)})))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (log/error e "HTTP request failed" {:url url})
      (throw (ex-info "HTTP request failed" {:url url} e)))))

;; ---------------------------------------------------------------------------
;; Pagination Helpers
;; ---------------------------------------------------------------------------
;;
;; Pseudovision list endpoints return an offset-pagination envelope:
;;   {:items [...] :pagination {:limit ... :offset ... :total ... :has_more ...}}
;; These helpers unwrap that envelope and transparently fetch every page so
;; callers always receive a flat vector of items.

(def ^:private page-size 500)

(defn- fetch-all-pages
  "Fetch every page from a paginated Pseudovision endpoint and return the
   concatenated items as a vector. `fetch-page` is a 2-arg fn (limit offset)
   returning the raw response body (an offset-pagination envelope)."
  [fetch-page]
  (loop [offset 0
         acc    []]
    (let [body  (fetch-page page-size offset)
          items (:items body)
          acc'  (into acc items)]
      (if (and (get-in body [:pagination :has_more]) (seq items))
        (recur (+ offset (count items)) acc')
        acc'))))

;; ---------------------------------------------------------------------------
;; Tag Management
;; ---------------------------------------------------------------------------

(defn add-tags!
  "Add tags to a media item in Pseudovision.

   Args:
     config - Pseudovision config map with :base-url
     media-item-id - Pseudovision media_items.id
     tags - Vector of tag strings

   Returns:
     Response map with :item-id and :tags-added"
  [config media-item-id tags]
  (request! :post
            (api-url config (str "/api/media-items/" media-item-id "/tags"))
            {:content-type :json
             :body (json/generate-string tags)}))

(defn get-tags
  "Get all tags for a media item.

   Returns vector of tag strings."
  [config media-item-id]
  (request! :get
            (api-url config (str "/api/media-items/" media-item-id "/tags"))
            {}))

(defn delete-tag!
  "Remove a specific tag from a media item."
  [config media-item-id tag]
  (request! :delete
            (api-url config (str "/api/media-items/" media-item-id "/tags/" tag))
            {}))

(defn list-all-tags
  "List all unique tags with usage counts.

   Returns vector of maps: [{:name 'comedy' :count 42} ...]"
  [config]
  (fetch-all-pages
   (fn [limit offset]
     (request! :get
               (api-url config "/api/tags")
               {:query-params {"limit" limit "offset" offset}}))))

;; ---------------------------------------------------------------------------
;; Media Sources
;; ---------------------------------------------------------------------------

(defn list-media-sources
  "List all configured media sources (local, plex, jellyfin, emby)."
  [config]
  (fetch-all-pages
   (fn [limit offset]
     (request! :get
               (api-url config "/api/media/sources")
               {:query-params {"limit" limit "offset" offset}}))))

(defn create-media-source!
  "Create a new media source.

   source-data fields:
     :name - Source name
     :kind - 'local', 'plex', 'jellyfin', or 'emby'
     :connection-config - Backend-specific connection map (optional)
     :path-replacements - Vector of path replacement maps (optional)"
  [config source-data]
  (request! :post
            (api-url config "/api/media/sources")
            {:content-type :json
             :json-params source-data}))

(defn delete-media-source!
  "Delete a media source by ID."
  [config source-id]
  (request! :delete
            (api-url config (str "/api/media/sources/" source-id))
            {}))

;; ---------------------------------------------------------------------------
;; Libraries
;; ---------------------------------------------------------------------------

(defn list-all-libraries
  "List all media libraries across all sources."
  [config]
  (fetch-all-pages
   (fn [limit offset]
     (request! :get
               (api-url config "/api/media/libraries")
               {:query-params {"limit" limit "offset" offset}}))))

(defn list-source-libraries
  "List all libraries for a specific media source."
  [config source-id]
  (request! :get
            (api-url config (str "/api/media/sources/" source-id "/libraries"))
            {}))

(defn create-library!
  "Create a library under a media source.

   library-data fields:
     :name - Library name
     :kind - 'movies', 'shows', 'music_videos', 'other_videos', 'songs', or 'images'
     :external-id - Backend-specific ID (optional)
     :should-sync - Whether to sync this library (optional)"
  [config source-id library-data]
  (request! :post
            (api-url config (str "/api/media/sources/" source-id "/libraries"))
            {:content-type :json
             :json-params library-data}))

(defn discover-libraries!
  "Discover and create libraries from a remote media source.

   Returns map with :discovered, :created, and :libraries counts."
  [config source-id]
  (request! :post
            (api-url config (str "/api/media/sources/" source-id "/libraries/discover"))
            {}))

(defn list-library-items
  "List media items in a library with optional pagination.

   opts fields:
     :attrs - Comma-separated attribute names to include in response
     :type - Filter by media type string
     :parent-id - Filter by parent item ID
     :limit - Max items to return in this batch (default: no limit, optional for pagination)
     :offset - Starting item index for pagination (0-based, optional)

   Returns vector of item maps (guaranteed to have :id). When neither :limit
   nor :offset is supplied, every page is fetched and the full set is returned.

   Example paginated usage:
     (list-library-items config lib-id {:limit 500 :offset 0})    ; Fetch first 500
     (list-library-items config lib-id {:limit 500 :offset 500})  ; Fetch next 500"
  [config library-id & [{:keys [attrs type parent-id limit offset]}]]
  (let [base-params (cond-> {}
                      attrs     (assoc "attrs" attrs)
                      type      (assoc "type" type)
                      parent-id (assoc "parent-id" (str parent-id)))
        fetch-page  (fn [lim off]
                      (request! :get
                                (api-url config (str "/api/media/libraries/" library-id "/items"))
                                {:query-params (assoc base-params "limit" lim "offset" off)}))]
    (if (or limit offset)
      ;; Caller is driving pagination explicitly: return just the requested page.
      (vec (:items (fetch-page (or limit page-size) (or offset 0))))
      ;; Otherwise fetch every page so callers get the complete library.
      (fetch-all-pages fetch-page))))

(defn scan-library!
  "Trigger an asynchronous library scan.

   Returns map with :message."
  [config library-id]
  (request! :post
            (api-url config (str "/api/media/libraries/" library-id "/scan"))
            {}))

;; ---------------------------------------------------------------------------
;; Media Items
;; ---------------------------------------------------------------------------

(defn get-media-item
  "Get a media item by ID."
  [config item-id]
  (request! :get
            (api-url config (str "/api/media/items/" item-id))
            {}))

(defn get-media-item-playback-url
  "Resolve a direct playback URL for a media item.

   Returns map with :url (string or nil) and :kind."
  [config item-id]
  (request! :get
            (api-url config (str "/api/media/items/" item-id "/playback-url"))
            {}))

(defn find-media-item-by-jellyfin-id
  "Find a Pseudovision media item by its Jellyfin remote-key.

   Scans all libraries for the matching item. Returns the first match or nil.
   Pass :library-id in opts to narrow the search to a single library."
  [config jellyfin-id & [{:keys [library-id]}]]
  (try
    (let [libraries (if library-id
                      [{:id library-id}]
                      (list-all-libraries config))]
      (loop [libs libraries]
        (when (seq libs)
          (let [lib (first libs)
                ;; Wrap individual library search in try-catch to handle failures gracefully
                match (try
                        (let [items (list-library-items config (:id lib) {:attrs "id,remote-key"})]
                          (some #(when (= (str (:remote-key %)) (str jellyfin-id)) %) items))
                        (catch Exception e
                          ;; Log warning but continue searching other libraries
                          (log/warn "Failed to search library, continuing to next library"
                                   {:library-id (:id lib)
                                    :jellyfin-id jellyfin-id
                                    :error (ex-message e)})
                          nil))]
            (if match
              match
              (recur (rest libs)))))))
    (catch Exception e
      (log/error e "find-media-item-by-jellyfin-id failed" {:jellyfin-id jellyfin-id})
      nil)))

;; ---------------------------------------------------------------------------
;; Collections
;; ---------------------------------------------------------------------------

(defn get-collections
  "List all collections (smart/manual/playlist/multi/trakt/rerun)."
  [config]
  (fetch-all-pages
   (fn [limit offset]
     (request! :get
               (api-url config "/api/media/collections")
               {:query-params {"limit" limit "offset" offset}}))))

(defn create-collection!
  "Create a new collection.

   collection-data fields:
     :name - Collection name (required)
     :kind - 'manual', 'smart', 'playlist', 'multi', 'trakt', or 'rerun' (optional)
     :use-custom-playback-order - Boolean (optional)
     :config - Kind-specific config map (optional)"
  [config collection-data]
  (request! :post
            (api-url config "/api/media/collections")
            {:content-type :json
             :json-params collection-data}))

;; ---------------------------------------------------------------------------
;; Schedule Management
;; ---------------------------------------------------------------------------

(defn create-schedule!
  "Create a new schedule.

   Args:
     config - Pseudovision config
     schedule-data - Map with :name and optional scheduling fields

   Returns:
     Created schedule with :id"
  [config schedule-data]
  (request! :post
            (api-url config "/api/schedules")
            {:content-type :json
             :json-params schedule-data}))

(defn get-schedule
  "Get schedule by ID."
  [config schedule-id]
  (request! :get
            (api-url config (str "/api/schedules/" schedule-id))
            {}))

(defn list-schedules
  "List all schedules."
  [config]
  (fetch-all-pages
   (fn [limit offset]
     (request! :get
               (api-url config "/api/schedules")
               {:query-params {"limit" limit "offset" offset}}))))

(defn update-schedule!
  "Update an existing schedule.

   schedule-data fields (all optional):
     :name - Schedule name
     :fixed-start-time-behavior - 'skip' or 'play'
     :shuffle-slots - Boolean
     :random-start-point - Boolean
     :keep-multi-part-together - Boolean
     :treat-collections-as-shows - Boolean"
  [config schedule-id schedule-data]
  (request! :put
            (api-url config (str "/api/schedules/" schedule-id))
            {:content-type :json
             :json-params schedule-data}))

(defn delete-schedule!
  "Delete a schedule by ID."
  [config schedule-id]
  (request! :delete
            (api-url config (str "/api/schedules/" schedule-id))
            {}))

;; ---------------------------------------------------------------------------
;; Slot Management
;; ---------------------------------------------------------------------------

(defn add-slot!
  "Add a slot to a schedule.

   Slot data fields:
     :slot-index - Position in schedule (0-based)
     :anchor - 'fixed' or 'sequential'
     :start-time - Time string like '18:00:00' (for fixed anchors)
     :fill-mode - 'once', 'count', 'block', or 'flood'
     :item-count - Number of items (for count mode)
     :block-duration - Duration string like 'PT2H' (for block mode)
     :collection-id - Collection to pull from
     :media-item-id - Specific item (overrides collection)
     :required-tags - Vector of tags (item must have ALL)
     :excluded-tags - Vector of tags (item must have NONE)
     :playback-order - 'chronological', 'random', 'shuffle', 'semi-sequential', etc.
     :marathon-batch-size - For semi-sequential mode

   Returns:
     Created slot with :id"
  [config schedule-id slot-data]
  (request! :post
            (api-url config (str "/api/schedules/" schedule-id "/slots"))
            {:content-type :json
             :json-params slot-data}))

(defn list-slots
  "List all slots for a schedule."
  [config schedule-id]
  (request! :get
            (api-url config (str "/api/schedules/" schedule-id "/slots"))
            {}))

(defn get-slot
  "Get a single slot by schedule ID and slot ID."
  [config schedule-id slot-id]
  (request! :get
            (api-url config (str "/api/schedules/" schedule-id "/slots/" slot-id))
            {}))

(defn update-slot!
  "Update a slot. slot-data is a partial map of any slot fields."
  [config schedule-id slot-id slot-data]
  (request! :put
            (api-url config (str "/api/schedules/" schedule-id "/slots/" slot-id))
            {:content-type :json
             :json-params slot-data}))

(defn delete-slot!
  "Delete a slot by schedule ID and slot ID."
  [config schedule-id slot-id]
  (request! :delete
            (api-url config (str "/api/schedules/" schedule-id "/slots/" slot-id))
            {}))

;; ---------------------------------------------------------------------------
;; Channel & Playout Management
;; ---------------------------------------------------------------------------

(defn list-channels
  "List all channels (a flat vector of channel records). Pass {:uuid uuid-str}
   to filter by UUID.

   Tolerates either response shape: a bare array, or the offset-pagination
   envelope {:items [...] :pagination {...}} the other list endpoints return.
   Previously this returned the raw body, so when the endpoint answered with the
   envelope, callers that map over the result (e.g. uuid->pv-id) iterated the
   envelope's top-level keys instead of the channel records — yielding an empty
   index and a spurious :not-found for every channel. A high page limit keeps
   the channel set (a handful of channels) on a single page."
  [config & [{:keys [uuid]}]]
  (let [body (request! :get
                       (api-url config "/api/channels")
                       {:query-params (cond-> {"limit" page-size "offset" 0}
                                        uuid (assoc "uuid" uuid))})]
    (if (and (map? body) (contains? body :items))
      (:items body)
      body)))

(defn get-channel
  "Get channel by ID."
  [config channel-id]
  (request! :get
            (api-url config (str "/api/channels/" channel-id))
            {}))

(defn create-channel!
  "Create a new channel.

   Channel data:
     :name - Channel name
     :uuid - Channel UUID (optional, will be generated if not provided)
     :number - Channel number string
     :description - Channel description (optional)

   Returns:
     Created channel with assigned :id"
  [config channel-data]
  (request! :post
            (api-url config "/api/channels")
            {:content-type :json
             :json-params channel-data}))

(defn update-channel!
  "Update channel configuration.

   Common updates:
     :schedule-id - Attach a schedule to the channel
     :name - Update channel name
     :number - Update channel number"
  [config channel-id updates]
  (request! :put
            (api-url config (str "/api/channels/" channel-id))
            {:content-type :json
             :json-params updates}))

(defn get-playout
  "Get the playout record for a channel, including build status and cursor."
  [config channel-id]
  (request! :get
            (api-url config (str "/api/channels/" channel-id "/playout"))
            {}))

(defn rebuild-playout!
  "Trigger playout rebuild for a channel.

   Options:
     :from - 'now' (delete all future events) or 'horizon' (extend future)
     :horizon - Number of days to generate (default 14)

   Returns:
     Map with :message, :events-generated, :horizon-days"
  [config channel-id & [{:keys [from horizon] :or {from "now" horizon 14}}]]
  (request! :post
            (api-url config (str "/api/channels/" channel-id "/playout"))
            {:query-params {"from" from "horizon" (str horizon)}}))

;; ---------------------------------------------------------------------------
;; Playout Events
;; ---------------------------------------------------------------------------

(defn list-playout-events
  "List upcoming scheduled playout events for a channel."
  [config channel-id]
  (request! :get
            (api-url config (str "/api/channels/" channel-id "/playout/events"))
            {}))

(defn inject-manual-event!
  "Inject a manual event into the playout timeline.

   event-data fields:
     :media-item-id - Media item ID (required)
     :start-at - ISO-8601 timestamp (required)
     :finish-at - ISO-8601 timestamp (required)
     :kind - 'content', 'pre', 'mid', 'post', 'pad', 'tail', 'fallback', or 'offline'
     :guide-start-at - ISO-8601 timestamp for EPG (optional)
     :guide-finish-at - ISO-8601 timestamp for EPG (optional)
     :custom-title - Override title (optional)"
  [config channel-id event-data]
  (request! :post
            (api-url config (str "/api/channels/" channel-id "/playout/events"))
            {:content-type :json
             :json-params event-data}))

(defn update-manual-event!
  "Update a manual playout event. event-data is a partial map of event fields."
  [config channel-id event-id event-data]
  (request! :put
            (api-url config (str "/api/channels/" channel-id "/playout/events/" event-id))
            {:content-type :json
             :json-params event-data}))

(defn delete-manual-event!
  "Delete a manual playout event."
  [config channel-id event-id]
  (request! :delete
            (api-url config (str "/api/channels/" channel-id "/playout/events/" event-id))
            {}))

;; ---------------------------------------------------------------------------
;; FFmpeg Profiles
;; ---------------------------------------------------------------------------

(defn list-ffmpeg-profiles
  "List all FFmpeg encoder profiles."
  [config]
  (request! :get
            (api-url config "/api/ffmpeg/profiles")
            {}))

(defn get-ffmpeg-profile
  "Get an FFmpeg profile by ID."
  [config profile-id]
  (request! :get
            (api-url config (str "/api/ffmpeg/profiles/" profile-id))
            {}))

(defn create-ffmpeg-profile!
  "Create a new FFmpeg encoder profile.

   profile-data fields:
     :name - Profile name (required)
     :config - Encoder configuration map (optional)"
  [config profile-data]
  (request! :post
            (api-url config "/api/ffmpeg/profiles")
            {:content-type :json
             :json-params profile-data}))

(defn update-ffmpeg-profile!
  "Update an FFmpeg profile. profile-data is a partial map."
  [config profile-id profile-data]
  (request! :put
            (api-url config (str "/api/ffmpeg/profiles/" profile-id))
            {:content-type :json
             :json-params profile-data}))

(defn delete-ffmpeg-profile!
  "Delete an FFmpeg profile. Returns map with :deleted true and :profile."
  [config profile-id]
  (request! :delete
            (api-url config (str "/api/ffmpeg/profiles/" profile-id))
            {}))

;; ---------------------------------------------------------------------------
;; Layered-grid scheduling: catalog aggregate + daily-slot ingestion
;;
;; Pseudovision normalises all JSON keys to kebab-case in both directions, so
;; these speak kebab-case on the wire. Case conversion to/from the snake_case
;; scheduling contracts lives in tunarr.scheduler.scheduling.integration.
;; ---------------------------------------------------------------------------

(defn get-catalog-aggregate
  "GET /api/catalog/aggregate — the deterministic library rollup behind a
   CatalogProfile (kebab-case). Both query params are optional:
     :channel — integer id, channel number, or exact channel name (scopes the
                profile); omitted ⇒ the full library.
     :tag     — explicit tag filter (e.g. \"channel:comedy\"); overrides the
                channel-name inference when both are given."
  [config & [{:keys [channel tag]}]]
  (request! :get
            (api-url config "/api/catalog/aggregate")
            {:query-params (cond-> {}
                             channel (assoc "channel" (str channel))
                             tag     (assoc "tag" tag))}))

(defn push-daily-slots!
  "POST /api/channels/:channel-id/daily-slots — ingest a kebab-case DailySlot
   vector. Pseudovision clears existing non-manual events in the slots' date
   range first, so the push is idempotent for that range. Returns the
   DailySlotIngestResult ({:ingested :skipped :errors :channel-id})."
  [config channel-id slots]
  (let [body (json/generate-string slots)
        url  (api-url config (str "/api/channels/" channel-id "/daily-slots"))]
    ;; Diagnostic: log exactly what goes on the wire so a rejection like
    ;; "Missing start_time" can be traced to the request body (key names,
    ;; value formats, envelope) rather than guessed at. `first-slot` is the
    ;; raw map (does it even carry :start_time?); `body-sample` is the actual
    ;; serialized JSON PV parses.
    (log/info "push-daily-slots!: request"
              {:url url
               :channel-id channel-id
               :slot-count (count slots)
               :first-slot (first slots)
               :body-length (count body)
               :body-sample (subs body 0 (min 800 (count body)))})
    (let [resp (request! :post url
                         {:content-type :json
                          :body body})]
      (log/info "push-daily-slots!: response" {:channel-id channel-id :response resp})
      resp)))

;; ---------------------------------------------------------------------------
;; Health & Version
;; ---------------------------------------------------------------------------

(defn health-check
  "Check if Pseudovision is reachable and healthy.

   Returns map with :status and :version info"
  [config]
  (try
    (let [version (request! :get (api-url config "/api/version") {})]
      {:status "ok"
       :version version
       :reachable true})
    (catch Exception e
      (log/error e "Pseudovision health check failed")
      {:status "error"
       :error (.getMessage e)
       :reachable false})))

;; ---------------------------------------------------------------------------
;; Backend Protocol Implementation
;; ---------------------------------------------------------------------------

(defrecord PseudovisionBackend [config]
  proto/ChannelBackend

  (create-channel [_ channel-spec]
    (log/info "Creating Pseudovision channel" {:name (:name channel-spec)})
    (create-channel! config channel-spec))

  (update-channel [_ channel-id updates]
    (update-channel! config channel-id updates))

  (delete-channel [_ channel-id]
    (try
      (request! :delete
                (api-url config (str "/api/channels/" channel-id))
                {})
      {:success true}
      (catch Exception e
        {:success false :message (.getMessage e)})))

  (get-channels [_]
    (list-channels config))

  (upload-schedule [this channel-id schedule]
    ;; Convert schedule spec to Pseudovision schedule+slots
    (log/info "Uploading schedule to Pseudovision"
              {:channel-id channel-id
               :slots (count (:slots schedule))})

    ;; Create schedule
    (let [sched (create-schedule! config {:name (:name schedule)})
          schedule-id (:id sched)]

      ;; Create slots
      (doseq [[idx slot] (map-indexed vector (:slots schedule))]
        (add-slot! config schedule-id
                   (assoc slot :slot-index idx)))

      ;; Attach schedule to channel and rebuild
      (update-channel! config channel-id {:schedule-id schedule-id})
      (rebuild-playout! config channel-id {:from "now" :horizon 14})

      {:success true :schedule-id schedule-id}))

  (get-schedule [_ channel-id]
    (let [channel (get-channel config channel-id)
          schedule-id (:schedule-id channel)]
      (when schedule-id
        (get-schedule config schedule-id))))

  (validate-config [_ config]
    (let [base-url (:base-url config)]
      (if (and base-url (string? base-url) (not (= "" base-url)))
        (let [health (health-check config)]
          (if (:reachable health)
            {:valid? true :version (:version health)}
            {:valid? false
             :errors ["Pseudovision is not reachable" (:error health)]}))
        {:valid? false
         :errors ["base-url is required and must be a non-empty string"]}))))

(defn create
  "Create a Pseudovision backend client.

   Config map should include:
     :base-url - Pseudovision API base URL (e.g. 'https://pseudovision.kube.sea.fudo.link')

   Returns:
     PseudovisionBackend record implementing ChannelBackend protocol"
  [config]
  (log/info "Creating Pseudovision backend client" {:base-url (:base-url config)})
  (->PseudovisionBackend config))

(defn get-config
  [client]
  (:config client))
