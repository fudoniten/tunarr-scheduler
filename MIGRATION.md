# ChannelFlow: Migration to Dual-Backend Support

## Project Rename

**Current:** `tunarr-scheduler`  
**New:** `channelflow`

**Rationale:** The project now functions as a generic IPTV channel scheduler that works with multiple backends (ErsatzTV, Tunarr, and potentially others). The name "channelflow" conveys the flow of content through channels, is backend-agnostic, and memorable.

**Status:** ✅ Approved

---

## Architecture Overview

### Dual-Backend Design

```
┌─────────────────────────────────────────────────────────────┐
│                      ChannelFlow Core                        │
│  ┌────────────────────────────────────────────────────┐     │
│  │  Media Ingestion (Jellyfin, NFO, etc)              │     │
│  │  ↓                                                  │     │
│  │  LLM-Powered Tagging & Categorization (tunabrain)  │     │
│  │  ↓                                                  │     │
│  │  Internal Catalog (PostgreSQL/Memory)              │     │
│  │  ↓                                                  │     │
│  │  Scheduling Engine (Core Logic)                    │     │
│  │  ↓                                                  │     │
│  │  Schedule Generator (Backend-Agnostic)             │     │
│  └────────────────────────────────────────────────────┘     │
│                           │                                  │
│              ┌────────────┴────────────┐                     │
│              ↓                         ↓                     │
│   ┌──────────────────┐      ┌──────────────────┐           │
│   │  ErsatzTV Client │      │   Tunarr Client  │           │
│   │                  │      │                  │           │
│   │  - YAML Export   │      │  - Manual Export │           │
│   │  - API Upload    │      │  - Recommend.    │           │
│   │  - Auto Sync     │      │  - (Future API)  │           │
│   └──────────────────┘      └──────────────────┘           │
│              │                         │                     │
└──────────────┼─────────────────────────┼─────────────────────┘
               ↓                         ↓
        ┌────────────┐           ┌────────────┐
        │  ErsatzTV  │           │   Tunarr   │
        └────────────┘           └────────────┘
```

---

## Phase 1: Foundation & Channel Mapping (Weeks 1-2)

### 1.1 Project Restructuring

**Goal:** Prepare codebase for multi-backend support

**Tasks:**

1. **Create backend abstraction layer**
   ```clojure
   ;; New namespace: channelflow.backends.protocol
   (defprotocol ChannelBackend
     "Protocol for IPTV channel backends"
     (create-channel [backend channel-spec])
     (update-channel [backend channel-id updates])
     (delete-channel [backend channel-id])
     (get-channels [backend])
     (upload-schedule [backend channel-id schedule])
     (get-schedule [backend channel-id])
     (validate-config [backend config]))
   ```

2. **Refactor configuration to support multiple backends**
   ```edn
   ;; resources/config.edn
   {:backends {
      :ersatztv {
        :enabled true
        :base-url #or [#env ERSATZTV_URL "http://localhost:8409"]
        :auto-sync true
        :default-streaming-mode "HLS Segmenter"}
      :tunarr {
        :enabled false
        :base-url #or [#env TUNARR_URL "http://localhost:8000"]
        :auto-sync false}}
    ;; ... rest of config
   }
   ```

3. **Update namespace structure**
   ```
   src/channelflow/
   ├── backends/
   │   ├── protocol.clj          # Backend protocol
   │   ├── ersatztv/
   │   │   ├── client.clj        # HTTP client for ErsatzTV API
   │   │   ├── yaml.clj          # YAML schedule generator
   │   │   └── mapping.clj       # Channel/schedule mapping
   │   └── tunarr/
   │       ├── client.clj        # Tunarr client (recommendations)
   │       └── export.clj        # Manual export formats
   ├── catalog/                  # Renamed from media/
   ├── curation/                 # LLM tagging
   ├── scheduling/               # Core scheduling engine
   └── http/                     # API server
   ```

**Acceptance Criteria:**
- [ ] Protocol defined with full docstrings
- [ ] Config supports multiple backends
- [ ] Namespaces reorganized
- [ ] All existing tests pass

---

### 1.2 Channel Mapping System

**Goal:** Create universal channel representation that maps to both backends

**Channel Schema (Backend-Agnostic):**
```clojure
(s/def ::channel-id keyword?)
(s/def ::channel-number (s/or :int int? :decimal (s/and string? #(re-matches #"\d+\.\d+" %))))
(s/def ::channel-name string?)
(s/def ::channel-description (s/nilable string?))
(s/def ::channel-logo-url (s/nilable string?))
(s/def ::streaming-mode #{:hls-segmenter :mpeg-ts :hls-direct :mpeg-ts-legacy})
(s/def ::watermark-enabled boolean?)
(s/def ::preferred-audio-language (s/nilable string?))
(s/def ::subtitle-mode #{:none :burned-in :external})

;; Generic channel spec
(s/def ::channel-spec
  (s/keys :req [::channel-id ::channel-name ::channel-number]
          :opt [::channel-description ::channel-logo-url 
                ::streaming-mode ::watermark-enabled
                ::preferred-audio-language ::subtitle-mode]))

;; Backend-specific extensions
(s/def ::ersatztv-channel-spec
  (s/merge ::channel-spec
           (s/keys :opt [::ersatz-streaming-mode 
                         ::ersatz-watermark-config
                         ::ersatz-fallback-filler])))

(s/def ::tunarr-channel-spec
  (s/merge ::channel-spec
           (s/keys :opt [::tunarr-on-demand-mode
                         ::tunarr-stealth-mode])))
```

**Mapping Functions:**
```clojure
;; channelflow.backends.ersatztv.mapping
(defn channel-spec->ersatztv
  "Convert generic channel spec to ErsatzTV API format"
  [channel-spec]
  {:number (::channel-number channel-spec)
   :name (::channel-name channel-spec)
   :streamingMode (streaming-mode->ersatz (::streaming-mode channel-spec))
   ;; ... etc
   })

(defn ersatztv->channel-spec
  "Convert ErsatzTV channel to generic spec"
  [ersatz-channel]
  {::channel-id (keyword (str "ch-" (:id ersatz-channel)))
   ::channel-number (:number ersatz-channel)
   ;; ... etc
   })
```

**Implementation:**
1. Create `channelflow.channels` namespace with universal schema
2. Implement mappers for ErsatzTV
3. Implement mappers for Tunarr (prepare for future API)
4. Add validation tests

**Acceptance Criteria:**
- [ ] Universal channel schema defined
- [ ] Bidirectional ErsatzTV mapping works
- [ ] Tunarr mapping stubbed out
- [ ] Channel CRUD operations work via protocol

---

## Phase 2: Tag System & Media Assignment (Weeks 3-4)

### 2.1 Tag Normalization for Both Backends

**Goal:** Ensure tags work consistently across backends

**ErsatzTV Considerations:**
- Supports custom collections (similar to tags)
- Supports "Smart Collections" with search queries
- Search syntax: `type:episode AND show_title:"Show Name"`

**Strategy:**
```clojure
;; Tags are stored in internal catalog (existing system)
;; Backend clients translate tags to backend-specific constructs

;; For ErsatzTV: Create Smart Collections per tag
(defn create-smart-collection-for-tag
  [ersatz-client tag media-items]
  (let [media-ids (map ::media/id media-items)
        ;; Generate ErsatzTV search query
        query (format "id:(%s)" (str/join " OR " media-ids))]
    (ersatz/create-smart-collection! 
      ersatz-client
      {:name (name tag)
       :query query})))
```

**Implementation:**
1. Keep existing tag normalization (working well)
2. Add ErsatzTV collection sync
3. Create mapping between internal tags and ErsatzTV collections
4. Add tag-based content selection for scheduling

**Acceptance Criteria:**
- [ ] Tags in catalog sync to ErsatzTV as Smart Collections
- [ ] Scheduler can select media by tag for both backends
- [ ] Tag CRUD operations trigger backend sync

---

### 2.2 Media Assignment to Channels

**Goal:** Support assigning media/collections to channels for both backends

**Common Abstraction:**
```clojure
(s/def ::content-source
  (s/keys :req [::source-type ::source-ref]
          :opt [::playback-order ::weight]))

(s/def ::source-type 
  #{:tag           ;; Media with specific tag
    :collection    ;; Named collection
    :search        ;; Search query
    :show          ;; Entire show
    :season        ;; Show season
    :playlist      ;; Custom playlist
    :media-ids})   ;; Explicit media IDs

(s/def ::playback-order
  #{:chronological :shuffle :random :weighted})

;; Example: Assign horror movies to channel
{::channel-id :horror-night
 ::content-sources [{::source-type :tag
                     ::source-ref :horror
                     ::playback-order :shuffle
                     ::weight 0.7}
                    {::source-type :tag
                     ::source-ref :thriller
                     ::playback-order :shuffle
                     ::weight 0.3}]}
```

**ErsatzTV Mapping:**
```clojure
;; Convert to ErsatzTV Sequential Schedule content sources
(defn content-source->ersatz-yaml
  [{::keys [source-type source-ref playback-order]}]
  (case source-type
    :tag {:search {:key (str "TAG_" (name source-ref))
                   :query (format "type:movie AND tags:%s" source-ref)
                   :order (playback-order->ersatz playback-order)}}
    :collection {:collection {:key (name source-ref)
                              :name source-ref}}
    ;; ... other types
    ))
```

**Implementation:**
1. Define universal content source schema
2. Create content source → ErsatzTV YAML mapper
3. Create content source → Tunarr export mapper
4. Add UI/API for assigning sources to channels
5. Store assignments in catalog

**Acceptance Criteria:**
- [ ] Can assign tags/collections to channels
- [ ] Assignment translates correctly to ErsatzTV YAML
- [ ] Multiple content sources per channel supported
- [ ] Weighted content selection works

---

## Phase 3: Basic Scheduling Blocks (Weeks 5-7)

### 3.1 Core Scheduling Primitives

**Goal:** Implement scheduling engine that generates backend-agnostic schedules

**Schedule Block Types:**
```clojure
(s/def ::block-type
  #{:recurring-series    ;; Same show, episodes in order, repeating
    :movie-block         ;; Movies from source, duration-based
    :time-slot           ;; Fixed time daily/weekly
    :random-shuffle      ;; Random selection from source
    :marathon            ;; All episodes of show(s) in sequence
    :themed-block})      ;; LLM-curated content for theme

(s/def ::schedule-block
  (s/keys :req [::block-type ::content-sources ::duration-or-count]
          :opt [::start-time ::end-time ::day-of-week 
                ::filler-config ::priority]))

;; Example: Morning cartoon block
{::block-type :themed-block
 ::content-sources [{::source-type :tag
                     ::source-ref :saturday-morning-cartoons}]
 ::start-time "06:00"
 ::end-time "12:00"
 ::day-of-week :saturday
 ::filler-config {::pre-roll [:station-id]
                  ::post-roll [:bumpers]
                  ::mid-roll [:commercials]}}
```

**Scheduler Engine Implementation:**
```clojure
(defn schedule-week!
  "Generate weekly schedule for channel"
  [engine catalog channel-spec]
  (let [blocks (get-channel-blocks catalog (::channel-id channel-spec))
        ;; Sort blocks by priority and time constraints
        sorted-blocks (sort-schedule-blocks blocks)
        ;; Generate time-aware schedule
        schedule (reduce 
                   (fn [acc block]
                     (schedule-block engine catalog acc block))
                   empty-schedule
                   sorted-blocks)]
    ;; Return backend-agnostic schedule
    schedule))

(defn schedule-block
  "Schedule a single block, respecting time constraints"
  [engine catalog schedule block]
  (case (::block-type block)
    :recurring-series (schedule-recurring-series engine catalog schedule block)
    :movie-block (schedule-movie-block engine catalog schedule block)
    :time-slot (schedule-time-slot engine catalog schedule block)
    ;; ... etc
    ))
```

**Backend Translation:**
```clojure
;; Generic schedule → ErsatzTV YAML
(defn schedule->ersatztv-yaml
  [schedule channel-spec]
  {:content (schedule->ersatz-content-sources schedule)
   :playout (schedule->ersatz-playout-instructions schedule)})

;; Generic schedule → Tunarr recommendation
(defn schedule->tunarr-recommendation
  [schedule channel-spec]
  ;; Generate human-readable recommendation document
  {:channel (::channel-name channel-spec)
   :week-schedule (format-tunarr-week schedule)
   :instructions (generate-manual-steps schedule)})
```

**Implementation Steps:**
1. Implement `schedule-week!` core logic
2. Add time-slot scheduling (fixed daily/weekly times)
3. Add recurring series scheduling
4. Add movie block scheduling
5. Add filler/padding logic
6. Create ErsatzTV YAML translator
7. Create Tunarr recommendation formatter

**Acceptance Criteria:**
- [ ] Can schedule recurring series blocks
- [ ] Can schedule movie blocks with duration limits
- [ ] Can schedule fixed time slots
- [ ] Filler content fills gaps appropriately
- [ ] Generates valid ErsatzTV YAML
- [ ] Generates useful Tunarr recommendations

---

### 3.2 ErsatzTV YAML Generation

**Goal:** Convert internal schedule representation to ErsatzTV Sequential Schedule YAML

**YAML Structure:**
```yaml
# Generated by ChannelFlow
# Channel: Horror Nights
# Generated: 2026-01-22T10:00:00Z

content:
  - search:
      key: "HORROR_80S"
      query: "type:movie AND genre:Horror AND year:>=1980 AND year:<=1989"
      order: shuffle
  
  - search:
      key: "HORROR_MODERN"
      query: "type:movie AND genre:Horror AND year:>=2000"
      order: shuffle
  
  - collection:
      key: "BUMPERS"
      name: "Horror Bumpers"

playout:
  # Prime time horror block (8pm-12am)
  - duration: "4 hours"
    content: "HORROR_80S"
    custom_title: "80s Horror Night"
    pad_to_next: "12:00"
  
  # Late night modern horror
  - duration: "3 hours"
    content: "HORROR_MODERN"
    pad_to_next: "03:00"
  
  # Filler content for off-hours
  - duration: "17 hours"
    content: "BUMPERS"
    pad_to_next: "20:00"
```

**Generator Implementation:**
```clojure
(ns channelflow.backends.ersatztv.yaml
  (:require [clj-yaml.core :as yaml]))

(defn generate-sequential-schedule
  "Generate ErsatzTV Sequential Schedule YAML from internal schedule"
  [schedule channel-spec]
  (let [content-sources (extract-content-sources schedule)
        playout-instructions (generate-playout-instructions schedule)]
    {:content content-sources
     :playout playout-instructions}))

(defn export-yaml
  "Export schedule as YAML string"
  [schedule channel-spec]
  (-> (generate-sequential-schedule schedule channel-spec)
      (yaml/generate-string :dumper-options {:flow-style :block})))

(defn save-yaml-schedule
  "Save schedule to file"
  [schedule channel-spec output-path]
  (spit output-path (export-yaml schedule channel-spec)))
```

**Acceptance Criteria:**
- [ ] Generates valid ErsatzTV Sequential Schedule YAML
- [ ] All schedule block types translate correctly
- [ ] Content sources properly defined
- [ ] Playout instructions maintain timing constraints
- [ ] YAML validates against ErsatzTV schema

---

## Phase 4: ErsatzTV API Integration (Weeks 8-10)

### 4.1 ErsatzTV API Client

**Goal:** Full HTTP client for ErsatzTV OpenAPI

**Client Structure:**
```clojure
(ns channelflow.backends.ersatztv.client
  (:require [clj-http.client :as http]
            [cheshire.core :as json]))

(defprotocol ErsatzTVClient
  ;; Channel operations
  (list-channels [client])
  (get-channel [client channel-id])
  (create-channel [client channel-spec])
  (update-channel [client channel-id updates])
  (delete-channel [client channel-id])
  
  ;; Schedule operations
  (list-schedules [client])
  (get-schedule [client schedule-id])
  (create-sequential-schedule [client schedule-name yaml-content])
  (update-schedule [client schedule-id updates])
  (delete-schedule [client schedule-id])
  
  ;; Playout operations
  (get-playout [client channel-id])
  (build-playout [client channel-id options])
  (reset-playout [client channel-id])
  
  ;; Collection operations
  (list-collections [client])
  (create-smart-collection [client collection-spec])
  (update-collection [client collection-id updates])
  
  ;; Media operations
  (search-media [client query])
  (get-media-sources [client]))

(defrecord HttpErsatzTVClient [base-url http-opts]
  ErsatzTVClient
  
  (list-channels [_]
    (-> (http/get (str base-url "/api/channels") http-opts)
        :body
        (json/parse-string true)))
  
  (create-channel [_ channel-spec]
    (-> (http/post (str base-url "/api/channels")
                   (assoc http-opts 
                          :body (json/generate-string channel-spec)
                          :content-type :json))
        :body
        (json/parse-string true)))
  
  ;; ... implement other methods
  )

(defn create-client
  [config]
  (->HttpErsatzTVClient 
    (:base-url config)
    {:socket-timeout 30000
     :connection-timeout 10000
     :accept :json}))
```

**Error Handling:**
```clojure
(defn safe-api-call
  [f error-msg]
  (try
    (f)
    (catch Exception e
      (log/error e error-msg)
      {:error error-msg
       :details (ex-message e)})))
```

**Acceptance Criteria:**
- [ ] All major API endpoints implemented
- [ ] Error handling and retries
- [ ] Logging of API calls
- [ ] Unit tests with mock responses
- [ ] Integration tests with real ErsatzTV instance

---

### 4.2 Automatic Schedule Upload & Sync

**Goal:** Automatically upload generated schedules to ErsatzTV

**Sync Flow:**
```clojure
(defn sync-channel-to-ersatztv!
  "Sync channel configuration and schedule to ErsatzTV"
  [ersatz-client catalog channel-id]
  (let [channel-spec (get-channel-spec catalog channel-id)
        schedule (generate-schedule catalog channel-spec)
        yaml (generate-sequential-schedule schedule channel-spec)]
    
    ;; 1. Ensure channel exists in ErsatzTV
    (ensure-channel-exists! ersatz-client channel-spec)
    
    ;; 2. Create/update sequential schedule
    (let [schedule-id (or (find-existing-schedule ersatz-client channel-id)
                          (create-schedule! ersatz-client channel-id yaml))]
      
      ;; 3. Update schedule content
      (update-schedule! ersatz-client schedule-id yaml)
      
      ;; 4. Build playout
      (build-playout! ersatz-client 
                      (::channel-id channel-spec)
                      {:mode "reset"})
      
      ;; 5. Return sync status
      {:status :success
       :channel-id channel-id
       :schedule-id schedule-id
       :timestamp (java.time.Instant/now)})))

(defn auto-sync-enabled-channels!
  "Sync all channels with auto-sync enabled"
  [system]
  (let [channels (get-channels-with-auto-sync (:catalog system))
        ersatz-client (get-in system [:backends :ersatztv :client])]
    (doseq [channel channels]
      (log/info "Auto-syncing channel" {:channel (::channel-id channel)})
      (sync-channel-to-ersatztv! ersatz-client (:catalog system) 
                                  (::channel-id channel)))))
```

**Scheduled Sync Job:**
```clojure
;; Add to system.clj
:channelflow/sync-job
{:schedule "0 0 * * *"  ;; Daily at midnight
 :enabled (get-in config [:backends :ersatztv :auto-sync])
 :handler (fn [system]
            (auto-sync-enabled-channels! system))}
```

**Manual Sync API:**
```clojure
;; Add to routes.clj
(POST "/api/channels/:channel-id/sync" [channel-id backend]
  (let [backend-kw (keyword backend)
        sync-fn (case backend-kw
                  :ersatztv sync-channel-to-ersatztv!
                  :tunarr export-tunarr-recommendation)]
    (sync-fn (get-backend-client system backend-kw)
             (:catalog system)
             (keyword channel-id))))
```

**Acceptance Criteria:**
- [ ] Manual sync works via API endpoint
- [ ] Automatic daily sync job works
- [ ] Sync handles errors gracefully
- [ ] Sync status tracked in database
- [ ] Can disable auto-sync per channel

---

## Phase 5: Fine-Tuned Scheduling (Weeks 11-13)

### 5.1 Day-Specific & Time-Specific Scheduling

**Goal:** Support sophisticated scheduling rules

**Enhanced Block Specification:**
```clojure
(s/def ::schedule-constraint
  (s/keys :opt [::days-of-week      ;; [:monday :tuesday]
                ::date-range        ;; {:start "2026-01-01" :end "2026-12-31"}
                ::time-range        ;; {:start "20:00" :end "23:00"}
                ::seasonal-tags     ;; [:halloween :christmas]
                ::daytime-only      ;; boolean
                ::priority          ;; 1-10
                ::rotation-policy   ;; :daily :weekly :monthly
                ]))

;; Example: Saturday morning cartoons only on weekends, 6am-12pm
{::block-type :themed-block
 ::content-sources [{::source-type :tag ::source-ref :cartoons}]
 ::schedule-constraint {::days-of-week [:saturday :sunday]
                        ::time-range {:start "06:00" :end "12:00"}
                        ::priority 10}}

;; Example: Horror movies only in October
{::block-type :movie-block
 ::content-sources [{::source-type :tag ::source-ref :horror}]
 ::schedule-constraint {::date-range {:start "2026-10-01" :end "2026-10-31"}
                        ::time-range {:start "20:00" :end "02:00"}
                        ::seasonal-tags [:halloween]}}
```

**Constraint Evaluation:**
```clojure
(defn block-applies-at?
  "Check if schedule block applies at given instant"
  [block instant timezone]
  (let [constraint (::schedule-constraint block)
        local-dt (t/in instant timezone)
        day-of-week (t/day-of-week local-dt)
        time-of-day (t/time local-dt)
        date (t/date local-dt)]
    
    (and
      ;; Check day of week
      (or (nil? (::days-of-week constraint))
          (contains? (set (::days-of-week constraint)) day-of-week))
      
      ;; Check time range
      (or (nil? (::time-range constraint))
          (time-in-range? time-of-day (::time-range constraint)))
      
      ;; Check date range
      (or (nil? (::date-range constraint))
          (date-in-range? date (::date-range constraint)))
      
      ;; Check seasonal tags
      (or (nil? (::seasonal-tags constraint))
          (seasonal-match? date (::seasonal-tags constraint))))))
```

**Intelligent Scheduling:**
```clojure
(defn schedule-with-constraints
  "Schedule blocks respecting all constraints"
  [blocks start-instant duration timezone]
  (let [end-instant (t/+ start-instant duration)]
    (loop [current start-instant
           schedule []
           remaining-blocks blocks]
      (if (t/>= current end-instant)
        schedule
        (let [;; Find applicable blocks at current time
              applicable (filter #(block-applies-at? % current timezone) 
                                 remaining-blocks)
              ;; Select highest priority block
              selected (first (sort-by ::priority > applicable))]
          (if selected
            (let [block-duration (calculate-block-duration selected)
                  next-instant (t/+ current block-duration)]
              (recur next-instant
                     (conj schedule {:block selected
                                     :start current
                                     :end next-instant})
                     remaining-blocks))
            ;; No applicable block, add filler
            (recur (t/+ current (t/new-duration 30 :minutes))
                   (conj schedule {:block ::filler
                                   :start current
                                   :end (t/+ current (t/new-duration 30 :minutes))})
                   remaining-blocks)))))))
```

**Acceptance Criteria:**
- [ ] Day-of-week constraints work
- [ ] Time-of-day constraints work
- [ ] Date range constraints work
- [ ] Seasonal constraints work
- [ ] Priority system determines block selection
- [ ] Filler content used when no blocks apply

---

### 5.2 LLM-Enhanced Scheduling

**Goal:** Use LLM to make intelligent scheduling decisions

**Use Cases:**
1. **Content selection** - "Pick the best horror movie for Saturday night"
2. **Transition smoothness** - "Don't schedule happy comedies after intense dramas"
3. **Audience targeting** - "Schedule content appropriate for time of day"
4. **Thematic coherence** - "Group related content together"

**Implementation:**
```clojure
(defn llm-select-content
  "Use LLM to select best content for time slot"
  [tunabrain catalog content-sources context]
  (let [candidates (get-candidate-media catalog content-sources)
        ;; Build prompt
        prompt (format 
                 "Select the best content for: %s\nTime: %s\nDay: %s\nPrevious: %s\nCandidates:\n%s"
                 (:theme context)
                 (:time context)
                 (:day context)
                 (:previous-content context)
                 (format-candidates candidates))
        ;; Get LLM recommendation
        response (tunabrain/complete tunabrain 
                                    {:prompt prompt
                                     :model "gpt-4o"
                                     :temperature 0.3})]
    ;; Parse LLM response and return selected media
    (parse-llm-selection response candidates)))

(defn schedule-with-llm-curation
  "Schedule blocks using LLM for intelligent content selection"
  [engine tunabrain catalog blocks]
  (reduce 
    (fn [schedule block]
      (let [context (build-scheduling-context schedule block)
            selected-content (llm-select-content tunabrain 
                                                catalog 
                                                (::content-sources block)
                                                context)]
        (conj schedule (assoc block ::selected-content selected-content))))
    []
    blocks))
```

**Acceptance Criteria:**
- [ ] LLM can select appropriate content for time slots
- [ ] Scheduling considers content flow/transitions
- [ ] LLM suggestions can be overridden
- [ ] Fallback to rule-based when LLM unavailable

---

## Phase 6: Testing & Documentation (Week 14)

### 6.1 Integration Testing

**Test Scenarios:**

1. **End-to-End ErsatzTV Flow**
   ```clojure
   (deftest ersatztv-full-flow
     (testing "Complete workflow with ErsatzTV"
       (with-ersatztv-instance
         ;; 1. Ingest media from Jellyfin
         (ingest-media! system :movies)
         
         ;; 2. Tag content with LLM
         (retag-library! system :movies)
         
         ;; 3. Create channel
         (create-channel! system horror-channel-spec)
         
         ;; 4. Generate schedule
         (schedule-week! system :horror-night)
         
         ;; 5. Sync to ErsatzTV
         (sync-channel-to-ersatztv! system :horror-night)
         
         ;; 6. Verify in ErsatzTV
         (is (ersatztv-channel-exists? "horror-night"))
         (is (ersatztv-schedule-valid? "horror-night")))))
   ```

2. **Dual-Backend Support**
   ```clojure
   (deftest dual-backend-support
     (testing "Same schedule works with both backends"
       (let [schedule (generate-schedule system test-channel)]
         ;; Export to ErsatzTV
         (is (valid-ersatztv-yaml? 
               (schedule->ersatztv-yaml schedule)))
         
         ;; Export to Tunarr
         (is (valid-tunarr-recommendation? 
               (schedule->tunarr-recommendation schedule))))))
   ```

3. **Schedule Constraints**
   ```clojure
   (deftest schedule-constraints-respected
     (testing "Scheduling respects time/day constraints"
       (let [blocks [(saturday-morning-block)
                     (prime-time-block)
                     (late-night-block)]
             schedule (schedule-week! system :test-channel blocks)]
         ;; Verify saturday morning block only on saturday 6am-12pm
         (is (all-instances-match-constraints? 
               schedule (saturday-morning-block)))
         ;; ... other assertions
         )))
   ```

**Acceptance Criteria:**
- [ ] All integration tests pass
- [ ] End-to-end flow works with real ErsatzTV
- [ ] Dual-backend exports validated
- [ ] Schedule constraints verified

---

### 6.2 Documentation

**Documentation To-Do:**

1. **README Update**
   - Rename project
   - Explain dual-backend architecture
   - Quick start for both ErsatzTV and Tunarr
   - Architecture diagram

2. **API Documentation**
   - OpenAPI spec for HTTP endpoints
   - Backend protocol documentation
   - Schedule format specification

3. **User Guides**
   - `docs/GETTING_STARTED.md` - Setup and first channel
   - `docs/BACKENDS.md` - Backend comparison and setup
   - `docs/SCHEDULING.md` - Scheduling concepts and blocks
   - `docs/LLM_CURATION.md` - Using LLM for content curation
   - `docs/ERSATZTV_INTEGRATION.md` - ErsatzTV-specific features
   - `docs/TUNARR_EXPORT.md` - Tunarr manual export workflow

4. **Developer Guides**
   - `docs/ARCHITECTURE.md` - System architecture
   - `docs/ADDING_BACKENDS.md` - How to add new backends
   - `docs/DEVELOPMENT.md` - Development setup
   - `CONTRIBUTING.md`

5. **Migration Guide**
   - For users of old tunarr-scheduler
   - Breaking changes
   - Configuration migration

**Acceptance Criteria:**
- [ ] README updated with new name
- [ ] All user guides written
- [ ] API documented with examples
- [ ] Migration guide complete

---

## Phase 7: Future Work (Post-MVP)

### 7.1 Scripted Schedule Support (ErsatzTV)

**Goal:** Allow running Clojure code directly in ErsatzTV

**Approach:**
```python
#!/usr/bin/env python3
# ersatz_clojure_wrapper.py
# Wrapper script that calls our Clojure scheduling engine

import sys
import subprocess
import json

def main():
    api_host = sys.argv[1]
    build_id = sys.argv[2]
    mode = sys.argv[3]
    custom_args = sys.argv[4] if len(sys.argv) > 4 else "{}"
    
    # Call our Clojure CLI
    result = subprocess.run([
        'clojure', '-M:schedule',
        '--api-host', api_host,
        '--build-id', build_id,
        '--mode', mode,
        '--custom-args', custom_args
    ], capture_output=True, text=True)
    
    if result.returncode != 0:
        print(f"Error: {result.stderr}", file=sys.stderr)
        sys.exit(1)
    
    print(result.stdout)

if __name__ == '__main__':
    main()
```

```clojure
;; New namespace: channelflow.backends.ersatztv.scripted
(defn run-as-scripted-schedule
  "Entry point when running as ErsatzTV scripted schedule"
  [api-host build-id mode custom-args]
  (let [ersatz-client (create-client {:base-url api-host})
        schedule (generate-schedule-from-args custom-args)]
    ;; Use ErsatzTV API to build playout directly
    (build-playout-via-api! ersatz-client build-id mode schedule)))
```

### 7.2 Web UI

**Features:**
- Visual schedule builder
- Drag-and-drop block arrangement
- Live preview of schedule
- Backend status dashboard
- Tag management interface
- LLM prompt customization

### 7.3 Bumper Generation & TTS

**Goal:** Generate custom bumpers with text-to-speech narration (nice-to-have, long-term)

**Status:** Deferred to post-MVP - this is a quality-of-life enhancement rather than core functionality.

**Features:**
- Script generation for bumpers (via tunabrain LLM)
- Text-to-speech synthesis integration
- Bumper template system
- Integration with channel schedules as filler content
- Multiple TTS provider support (OpenAI, Google, AWS Polly, local)

**Implementation Notes:**
```clojure
;; When implemented, bumpers will be content sources
{::source-type :bumper
 ::source-ref :station-id-bumper
 ::tts-config {::voice "alloy"
               ::provider :openai
               ::script-template "You're watching {{channel-name}}"}}
```

**Acceptance Criteria:**
- [ ] Can generate bumper scripts via LLM
- [ ] Can synthesize audio via TTS providers
- [ ] Bumpers can be used as filler in schedules
- [ ] Multiple TTS providers supported

---

### 7.4 Advanced Features

- **Multi-week scheduling** - Plan multiple weeks in advance
- **A/B testing** - Compare different schedules
- **Analytics integration** - Track what content performs best
- **Community schedules** - Share schedule templates
- **Mobile app** - Manage schedules on the go
- **Webhook support** - Notify external systems of schedule changes

### 7.5 Additional Backends

- **Plex** - Direct Plex DVR integration
- **Jellyfin Live TV** - Direct Jellyfin integration
- **Custom IPTV** - Generic M3U/XMLTV generation
- **YouTube Live** - Stream to YouTube Live

---

## Migration Checklist

### Pre-Migration
- [ ] Backup existing tunarr-scheduler database
- [ ] Export current configuration
- [ ] Document current channel setups
- [ ] Test with ErsatzTV instance

### During Migration
- [ ] Rename project to channelflow
- [ ] Update all namespaces
- [ ] Refactor to backend protocol
- [ ] Implement ErsatzTV client
- [ ] Add YAML generation
- [ ] Update configuration format
- [ ] Migrate existing data

### Post-Migration
- [ ] Verify all tests pass
- [ ] Update documentation
- [ ] Test with real ErsatzTV instance
- [ ] Create sample configurations
- [ ] Write migration guide for users

---

## Dependencies to Add

```clojure
;; deps.edn additions
{:deps {
  ;; Existing deps...
  
  ;; YAML generation
  clj-yaml/clj-yaml {:mvn/version "1.0.27"}
  
  ;; Enhanced HTTP client features
  com.cognitect/http-client {:mvn/version "1.0.126"}}}
```

---

## Configuration Migration

### Old Format (tunarr-scheduler)
```edn
{:tunarr {:base-url "http://localhost:8000"}
 :channels {:horror-night {:id "horror-1" :name "Horror Night"}}}
```

### New Format (channelflow)
```edn
{:backends {
   :ersatztv {:enabled true :base-url "http://localhost:8409" :auto-sync true}
   :tunarr {:enabled false :base-url "http://localhost:8000"}}
 :channels {
   :horror-night {
     ::channel-id :horror-night
     ::channel-name "Horror Night"
     ::channel-number 100
     ::backends #{:ersatztv}  ;; Which backends to sync to
     ::auto-sync true}}}
```

---

## Risk Mitigation

### Risks & Mitigation Strategies

1. **ErsatzTV API Instability**
   - **Risk:** API changes break integration
   - **Mitigation:** Version checking, graceful degradation, fallback to manual export

2. **Complex Migration**
   - **Risk:** Users struggle to migrate
   - **Mitigation:** Automated migration script, clear documentation, support channel

3. **Performance with Large Catalogs**
   - **Risk:** Slow scheduling with 5000+ items
   - **Mitigation:** Batch operations, caching, incremental updates

4. **Backend Feature Parity**
   - **Risk:** ErsatzTV and Tunarr support different features
   - **Mitigation:** Common subset well-defined, backend-specific extensions documented

5. **LLM Costs**
   - **Risk:** Expensive LLM calls for large libraries
   - **Mitigation:** Caching, local models (Ollama), rate limiting

---

## Success Metrics

- [ ] Can ingest 5000+ media items in < 5 minutes
- [ ] Can generate weekly schedule in < 30 seconds
- [ ] Can sync to ErsatzTV in < 10 seconds
- [ ] 100% of schedule blocks translate correctly to YAML
- [ ] Zero manual intervention required for ErsatzTV sync
- [ ] Tunarr users can export usable recommendations
- [ ] All integration tests pass
- [ ] Documentation covers all common use cases

---

## Timeline Summary

- **Weeks 1-2:** Foundation & channel mapping
- **Weeks 3-4:** Tag system & media assignment
- **Weeks 5-7:** Basic scheduling blocks
- **Weeks 8-10:** ErsatzTV API integration
- **Weeks 11-13:** Fine-tuned scheduling
- **Week 14:** Testing & documentation

**Total:** ~14 weeks (3.5 months) for MVP

---

## Design Decisions

1. **Project Naming:** ✅ `channelflow` selected

2. **Backend Strategy:** ✅ Dual-backend support (ErsatzTV + Tunarr)
   - ErsatzTV: Full API integration with YAML generation and auto-sync
   - Tunarr: Manual export and recommendations (until API available)

3. **Implementation Approach:** ✅ YAML Sequential Schedules (Phase 1)
   - Scripted Schedules deferred to Phase 7 (future work)

4. **Integration Level:** ✅ Full integration (Option C)
   - Generate YAML files
   - Provide API client to POST schedules  
   - Automatic channel creation/updates and playout building

5. **Database Strategy:** ✅ Keep internal PostgreSQL catalog
   - Remains backend-agnostic
   - No ErsatzTV-specific optimizations needed

6. **Feature Priorities:**
   - ✅ Channel mapping (Phase 1)
   - ✅ Tags working (Phase 2)
   - ✅ Common functionality for both backends (Phase 2)
   - ✅ Basic scheduling blocks (Phase 3)
   - ✅ Fine-tuned day/time scheduling (Phase 5)
   - ⏸️ Bumper generation → **Moved to Phase 7** (nice-to-have, long-term)

---

## Implementation Plan

### Immediate Next Steps

1. ✅ **Document:** Migration plan finalized in `MIGRATION.md` on master
2. **Setup:** Create `channelflow-migration` branch for implementation work
3. **Phase 1.1:** Begin project restructuring
   - Create backend protocol abstraction
   - Refactor configuration
   - Update namespace structure
4. **Phase 1.2:** Implement channel mapping system
5. **Continue:** Work through phases incrementally

### Branch Strategy

- **master**: Contains stable code + this migration plan
- **channelflow-migration**: Active development branch for migration work
- **Merge strategy**: Merge phases incrementally as they're completed and tested

### Communication

This document serves as the single source of truth for the migration. Updates will be made here as phases complete and decisions evolve.

---

## Testing Checkpoints & Verification

This section identifies natural stopping points during the migration where manual testing should be performed to verify functionality before proceeding.

### Checkpoint 1: Backend Protocol & Configuration (End of Phase 1.1)

**When:** After completing project restructuring

**What to test:**
1. **Configuration Loading**
   - Start the application and verify config loads without errors
   - Check that backend configuration is properly parsed
   - Verify environment variable overrides work (ERSATZTV_URL, TUNARR_URL)

2. **Protocol Definition**
   - Verify the protocol compiles and namespaces are accessible
   - Check that stub implementations can be created
   - Test that the system component starts up with mock backends

**How to verify:**
```bash
# Start REPL and verify config
clojure -M:repl
```

```clojure
;; In REPL
(require '[channelflow.config :as config])
(require '[channelflow.backends.protocol :as proto])

;; Should load without errors
(def cfg (config/load-config))

;; Check backends config
(get-in cfg [:backends :ersatztv :base-url])
;; => "http://localhost:8409" (or your env var)

;; Verify protocol is defined
(methods proto/ChannelBackend)
;; Should list all protocol methods
```

**Success criteria:**
- ✅ Application starts without errors
- ✅ Config file loads and environment variables override defaults
- ✅ Backend protocol defined and accessible
- ✅ All existing tests still pass

---

### Checkpoint 2: Channel Mapping (End of Phase 1.2)

**When:** After implementing universal channel schema and mappers

**What to test:**
1. **Channel Spec Creation**
   - Create a channel spec using the universal schema
   - Validate specs with spec/valid?
   - Test that invalid specs are rejected

2. **ErsatzTV Mapping**
   - Convert channel spec to ErsatzTV format
   - Convert ErsatzTV channel back to spec
   - Verify round-trip conversion preserves data

**How to verify:**
```clojure
(require '[channelflow.channels :as ch])
(require '[channelflow.backends.ersatztv.mapping :as ersatz-map])

;; Create a test channel spec
(def test-channel
  {::ch/channel-id :test-horror
   ::ch/channel-name "Horror Night"
   ::ch/channel-number 100
   ::ch/streaming-mode :hls-segmenter})

;; Validate spec
(clojure.spec.alpha/valid? ::ch/channel-spec test-channel)
;; => true

;; Convert to ErsatzTV format
(def ersatz-channel (ersatz-map/channel-spec->ersatztv test-channel))
ersatz-channel
;; Should show properly formatted ErsatzTV channel

;; Convert back
(def round-trip (ersatz-map/ersatztv->channel-spec ersatz-channel))
;; Should match original (minus default values)

;; Test with real ErsatzTV API (if available)
(def client (ersatz/create-client {:base-url "http://localhost:8409"}))
(ersatz/list-channels client)
;; Should return list of existing channels
```

**Success criteria:**
- ✅ Channel specs validate correctly
- ✅ Invalid specs are rejected with clear error messages
- ✅ Bidirectional mapping preserves all required fields
- ✅ Can successfully call ErsatzTV API (if instance available)
- ✅ All tests pass including new mapping tests

---

### Checkpoint 3: Tag Sync to ErsatzTV (End of Phase 2.1)

**When:** After implementing tag-to-collection sync

**What to test:**
1. **Tag Creation and Sync**
   - Tag some media items in the internal catalog
   - Trigger sync to ErsatzTV
   - Verify Smart Collections created in ErsatzTV

2. **Tag Updates**
   - Modify tags on media items
   - Re-sync
   - Verify ErsatzTV collections updated

**How to verify:**
```clojure
(require '[channelflow.curation.tags :as tags])
(require '[channelflow.backends.ersatztv.client :as ersatz])

;; Tag some media
(def catalog (:catalog system))
(tags/tag-media! catalog :media-123 #{:horror :80s})
(tags/tag-media! catalog :media-456 #{:horror :modern})

;; Get all media with horror tag
(def horror-items (tags/find-by-tag catalog :horror))
(count horror-items)
;; => 2

;; Sync tags to ErsatzTV
(def ersatz-client (get-in system [:backends :ersatztv :client]))
(require '[channelflow.backends.ersatztv.collections :as coll])
(coll/sync-tag-to-collection! ersatz-client catalog :horror)

;; Verify in ErsatzTV
(def collections (ersatz/list-collections ersatz-client))
;; Should contain a "horror" Smart Collection

;; Check the collection query
;; Log into ErsatzTV UI at http://localhost:8409
;; Navigate to Collections → Find "horror" collection
;; Verify it contains the correct media items
```

**Manual verification in ErsatzTV UI:**
1. Open http://localhost:8409
2. Navigate to Collections
3. Find the "horror" Smart Collection
4. Click to view - should show 2 items (media-123 and media-456)
5. Check the search query - should reference the tagged media

**Success criteria:**
- ✅ Tags sync to ErsatzTV as Smart Collections
- ✅ Collections contain correct media items
- ✅ Tag updates trigger collection updates
- ✅ Multiple tags create multiple collections
- ✅ Can verify collections in ErsatzTV UI

---

### Checkpoint 4: Content Source Assignment (End of Phase 2.2)

**When:** After implementing media assignment to channels

**What to test:**
1. **Assign Content to Channel**
   - Create a channel spec
   - Assign content sources (tags, collections)
   - Verify assignments stored in catalog

2. **Content Source Retrieval**
   - Query what media is available for a channel
   - Test weighted selection
   - Test different playback orders

**How to verify:**
```clojure
(require '[channelflow.media.collection :as coll])

;; Create channel with content sources
(def horror-channel
  {::ch/channel-id :horror-night
   ::ch/channel-name "Horror Night"
   ::ch/channel-number 100
   ::ch/content-sources 
   [{::coll/source-type :tag
     ::coll/source-ref :horror
     ::coll/playback-order :shuffle
     ::coll/weight 0.7}
    {::coll/source-type :tag
     ::coll/source-ref :thriller
     ::coll/playback-order :shuffle
     ::coll/weight 0.3}]})

;; Store channel
(ch/save-channel! catalog horror-channel)

;; Retrieve available media for channel
(def available-media (coll/get-channel-media catalog :horror-night))
(count available-media)
;; Should show all horror + thriller tagged items

;; Test weighted selection
(def selected (coll/select-next-item catalog :horror-night {:strategy :weighted}))
;; Run multiple times - 70% should be horror, 30% thriller
```

**Success criteria:**
- ✅ Can assign multiple content sources to a channel
- ✅ Content sources stored and retrieved correctly
- ✅ Can query all available media for a channel
- ✅ Weighted selection approximately matches weights
- ✅ Different playback orders work as expected

---

### Checkpoint 5: Basic Schedule Generation (End of Phase 3.1)

**When:** After implementing core scheduling engine

**What to test:**
1. **Schedule Block Creation**
   - Create different types of schedule blocks
   - Validate block specs
   - Test duration calculations

2. **Schedule Generation**
   - Generate a simple weekly schedule
   - Verify blocks appear in correct order
   - Check time constraints respected

**How to verify:**
```clojure
(require '[channelflow.scheduling.engine :as sched])

;; Create schedule blocks
(def morning-block
  {::sched/block-type :themed-block
   ::sched/content-sources [{::coll/source-type :tag
                             ::coll/source-ref :cartoons}]
   ::sched/start-time "06:00"
   ::sched/end-time "12:00"
   ::sched/days-of-week #{:saturday :sunday}})

(def evening-block
  {::sched/block-type :movie-block
   ::sched/content-sources [{::coll/source-type :tag
                             ::coll/source-ref :horror}]
   ::sched/start-time "20:00"
   ::sched/end-time "23:00"})

;; Generate schedule
(def schedule 
  (sched/schedule-week! 
    (:scheduler system)
    catalog
    horror-channel
    [morning-block evening-block]))

;; Inspect schedule
(count schedule)
;; Should show scheduled items for the week

;; Check first Saturday morning
(def saturday-morning 
  (->> schedule
       (filter #(and (= :saturday (t/day-of-week (:start %)))
                     (>= (t/hour (:start %)) 6)
                     (< (t/hour (:start %)) 12)))))

;; Should only contain cartoon content
(every? #(contains? (:tags %) :cartoons) saturday-morning)
;; => true
```

**Manual verification:**
1. Print the schedule in human-readable format
2. Verify blocks appear at correct days/times
3. Check that filler content fills gaps
4. Ensure no overlapping content

**Success criteria:**
- ✅ Can create and validate schedule blocks
- ✅ Weekly schedule generated successfully
- ✅ Time constraints respected (morning/evening)
- ✅ Day constraints respected (weekend only)
- ✅ Filler content added where needed
- ✅ No overlapping content

---

### Checkpoint 6: YAML Generation (End of Phase 3.2)

**When:** After implementing ErsatzTV YAML export

**What to test:**
1. **YAML Structure**
   - Generate YAML from schedule
   - Validate YAML syntax
   - Check content sources formatted correctly

2. **YAML Import to ErsatzTV**
   - Save YAML to file
   - Manually import into ErsatzTV
   - Verify schedule appears correctly

**How to verify:**
```clojure
(require '[channelflow.backends.ersatztv.yaml :as yaml])

;; Generate YAML from schedule
(def yaml-str (yaml/export-yaml schedule horror-channel))

;; Print to see structure
(println yaml-str)

;; Save to file
(yaml/save-yaml-schedule schedule horror-channel "/tmp/horror-night.yaml")

;; Validate YAML parses
(clj-yaml.core/parse-string yaml-str)
;; Should parse without errors
```

**Manual verification in ErsatzTV:**
1. Open the generated YAML file at `/tmp/horror-night.yaml`
2. Verify structure looks correct:
   - `content:` section lists all content sources
   - `playout:` section has schedule blocks
   - Times and durations are reasonable
3. In ErsatzTV UI (http://localhost:8409):
   - Navigate to Schedules
   - Click "Import Sequential Schedule"
   - Upload the YAML file
   - Verify it imports without errors
4. Assign schedule to the horror channel
5. Build playout
6. Check the EPG to see scheduled content

**Success criteria:**
- ✅ YAML generates without errors
- ✅ YAML is valid syntax
- ✅ Content sources properly formatted
- ✅ Playout instructions include all blocks
- ✅ YAML imports into ErsatzTV successfully
- ✅ Schedule appears in ErsatzTV EPG
- ✅ Playback works in ErsatzTV player

---

### Checkpoint 7: ErsatzTV API Integration (End of Phase 4.1)

**When:** After implementing full ErsatzTV HTTP client

**What to test:**
1. **Channel CRUD Operations**
   - Create channel via API
   - Update channel settings
   - Delete channel
   - List all channels

2. **Schedule Operations**
   - Upload schedule via API
   - Update existing schedule
   - Delete schedule
   - Trigger playout build

**How to verify:**
```clojure
(def client (ersatz/create-client {:base-url "http://localhost:8409"}))

;; Test channel creation
(def created-channel
  (ersatz/create-channel 
    client
    (ersatz-map/channel-spec->ersatztv horror-channel)))

(def channel-id (:id created-channel))

;; Verify channel exists
(def retrieved (ersatz/get-channel client channel-id))
(:name retrieved)
;; => "Horror Night"

;; Update channel
(ersatz/update-channel client channel-id {:name "Horror Night HD"})

;; List all channels
(def all-channels (ersatz/list-channels client))
(map :name all-channels)
;; Should include "Horror Night HD"

;; Create schedule
(def schedule-id
  (ersatz/create-sequential-schedule 
    client
    "Horror Night Schedule"
    yaml-str))

;; Build playout
(ersatz/build-playout client channel-id {:mode "reset"})

;; Verify playout built
(def playout (ersatz/get-playout client channel-id))
(:itemCount playout)
;; Should show number of scheduled items
```

**Manual verification:**
1. Check ErsatzTV UI - channel should exist
2. Channel settings should match what was sent via API
3. Schedule should be associated with channel
4. EPG should show scheduled content
5. Stream should be playable

**Success criteria:**
- ✅ Can create channels via API
- ✅ Can update channel settings
- ✅ Can upload schedules via API
- ✅ Can trigger playout builds
- ✅ Playout builds successfully
- ✅ EPG shows correct schedule
- ✅ Stream is playable

---

### Checkpoint 8: Automatic Sync (End of Phase 4.2)

**When:** After implementing auto-sync functionality

**What to test:**
1. **Manual Sync Trigger**
   - Call sync endpoint
   - Verify channel and schedule updated in ErsatzTV
   - Check sync status returned

2. **Automatic Sync Job**
   - Enable auto-sync in config
   - Wait for scheduled run (or trigger manually)
   - Verify all auto-sync channels updated

**How to verify:**
```clojure
(require '[channelflow.backends.ersatztv.sync :as sync])

;; Manual sync
(def sync-result
  (sync/sync-channel-to-ersatztv!
    ersatz-client
    catalog
    :horror-night))

sync-result
;; => {:status :success
;;     :channel-id :horror-night
;;     :schedule-id "abc123"
;;     :timestamp #inst "2026-01-22T..."}

;; Check sync status
(def status (sync/get-sync-status catalog :horror-night))
(:last-sync status)
;; Should show recent timestamp

;; Test auto-sync job
(sync/auto-sync-enabled-channels! system)
;; Should sync all channels with :auto-sync true
```

**Manual verification via API:**
```bash
# Trigger manual sync via HTTP API
curl -X POST http://localhost:3000/api/channels/horror-night/sync?backend=ersatztv

# Should return sync status
# Check logs for sync activity
```

**Verification in ErsatzTV:**
1. Make a change to content or schedule in ChannelFlow
2. Trigger sync (manual or wait for auto)
3. Check ErsatzTV UI - changes should be reflected
4. Check EPG - should show updated schedule
5. Verify playout was rebuilt automatically

**Success criteria:**
- ✅ Manual sync endpoint works
- ✅ Sync creates/updates channel in ErsatzTV
- ✅ Sync uploads latest schedule
- ✅ Sync rebuilds playout automatically
- ✅ Auto-sync job runs on schedule
- ✅ Sync errors handled gracefully
- ✅ Sync status tracked and retrievable

---

### Checkpoint 9: Time Constraints (End of Phase 5.1)

**When:** After implementing day/time-specific scheduling

**What to test:**
1. **Day of Week Constraints**
   - Create blocks that only run on specific days
   - Generate schedule
   - Verify blocks only appear on correct days

2. **Time Range Constraints**
   - Create blocks with start/end times
   - Verify blocks don't exceed time ranges
   - Test blocks that span midnight

3. **Combined Constraints**
   - Create blocks with both day and time constraints
   - Test priority resolution when blocks overlap

**How to verify:**
```clojure
;; Weekend morning cartoons only
(def weekend-block
  {::sched/block-type :themed-block
   ::sched/content-sources [{::coll/source-type :tag
                             ::coll/source-ref :cartoons}]
   ::sched/schedule-constraint
   {::sched/days-of-week #{:saturday :sunday}
    ::sched/time-range {:start "06:00" :end "12:00"}
    ::sched/priority 10}})

;; Halloween horror in October only
(def halloween-block
  {::sched/block-type :movie-block
   ::sched/content-sources [{::coll/source-type :tag
                             ::coll/source-ref :horror}]
   ::sched/schedule-constraint
   {::sched/date-range {:start "2026-10-01" :end "2026-10-31"}
    ::sched/time-range {:start "20:00" :end "02:00"}
    ::sched/seasonal-tags [:halloween]
    ::sched/priority 8}})

;; Generate schedule for October
(def oct-schedule
  (sched/schedule-with-constraints
    [weekend-block halloween-block]
    (t/instant "2026-10-01T00:00:00Z")
    (t/new-duration 31 :days)
    "America/New_York"))

;; Verify weekend block only on Sat/Sun 6am-12pm
(def weekend-items
  (filter 
    (fn [item]
      (and (#{:saturday :sunday} (t/day-of-week (:start item)))
           (>= (t/hour (:start item)) 6)
           (< (t/hour (:start item)) 12)))
    oct-schedule))

;; All should be cartoons
(every? #(= (:block-type %) :themed-block) weekend-items)
;; => true

;; Verify Halloween block appears nightly 8pm-2am in October
(def halloween-items
  (filter
    (fn [item]
      (and (= (:block-type item) :movie-block)
           (= (t/month (:start item)) 10)))
    oct-schedule))

;; Should have ~31 instances (one per night)
(count halloween-items)
;; => ~31
```

**Manual verification:**
1. Generate schedules for different time periods
2. Print in calendar format
3. Visually verify blocks appear at correct times
4. Check edge cases:
   - Blocks that span midnight
   - DST transitions
   - Leap days
   - Year boundaries

**Success criteria:**
- ✅ Day-of-week constraints work correctly
- ✅ Time-range constraints enforced
- ✅ Date-range constraints work (seasonal)
- ✅ Priority system selects correct block when multiple apply
- ✅ Filler content used when no blocks apply
- ✅ Midnight-spanning blocks handled correctly
- ✅ DST transitions don't break scheduling

---

### Checkpoint 10: End-to-End Flow (End of Phase 6.1)

**When:** After completing integration tests, before final release

**What to test:**
Complete workflow from media ingestion to playback

**Full workflow test:**
1. **Media Ingestion**
   - Ingest media from Jellyfin (or other source)
   - Verify media appears in catalog

2. **LLM Tagging**
   - Run tunabrain tagging on library
   - Verify tags assigned appropriately
   - Check tag quality

3. **Sync Tags to ErsatzTV**
   - Sync tags as collections
   - Verify collections in ErsatzTV

4. **Create Channel**
   - Define channel with content sources
   - Assign tags/collections to channel

5. **Generate Schedule**
   - Create schedule blocks
   - Generate weekly schedule
   - Verify schedule looks correct

6. **Sync to ErsatzTV**
   - Trigger sync
   - Verify channel created
   - Verify schedule uploaded
   - Verify playout built

7. **Playback**
   - Open stream in media player
   - Verify content plays
   - Check EPG data
   - Verify schedule followed

**How to verify:**
```clojure
;; Full end-to-end test
(defn test-full-workflow []
  (let [system (component/start (system/create-system))]
    
    ;; 1. Ingest media
    (println "Ingesting media...")
    (media/ingest-jellyfin-library! system "Movies")
    (Thread/sleep 5000)  ;; Wait for ingestion
    
    ;; 2. Tag content
    (println "Tagging content...")
    (tunabrain/retag-library! system "Movies")
    (Thread/sleep 30000)  ;; Wait for LLM tagging
    
    ;; 3. Sync tags
    (println "Syncing tags to ErsatzTV...")
    (ersatz-coll/sync-all-tags! system)
    
    ;; 4. Create channel
    (println "Creating channel...")
    (def channel-result
      (ch/create-channel! 
        (:catalog system)
        {::ch/channel-id :test-channel
         ::ch/channel-name "Test Channel"
         ::ch/channel-number 999
         ::ch/content-sources
         [{::coll/source-type :tag
           ::coll/source-ref :action}]}))
    
    ;; 5. Generate schedule
    (println "Generating schedule...")
    (def schedule
      (sched/schedule-week!
        (:scheduler system)
        (:catalog system)
        channel-result
        [simple-movie-block]))
    
    ;; 6. Sync to ErsatzTV
    (println "Syncing to ErsatzTV...")
    (def sync-result
      (sync/sync-channel-to-ersatztv!
        (get-in system [:backends :ersatztv :client])
        (:catalog system)
        :test-channel))
    
    (println "Sync result:" sync-result)
    (println "✅ End-to-end test complete!")
    (println "Open ErsatzTV and check channel 999")
    
    system))
```

**Manual verification steps:**
1. Run the full workflow test
2. Watch logs for any errors
3. Check ErsatzTV UI:
   - Channel 999 should exist
   - Collections should be synced
   - Schedule should be active
   - EPG should show content
4. Open stream in VLC or browser:
   ```bash
   vlc http://localhost:8409/iptv/channel/999.m3u8
   ```
5. Verify:
   - Stream plays without buffering
   - Content matches schedule
   - Transitions are smooth
   - EPG data displays correctly

**Success criteria:**
- ✅ Complete workflow runs without errors
- ✅ Media ingested and tagged correctly
- ✅ Tags synced to ErsatzTV collections
- ✅ Channel created successfully
- ✅ Schedule generated and uploaded
- ✅ Playout built and active
- ✅ Stream plays correctly
- ✅ EPG matches schedule
- ✅ Content follows schedule blocks
- ✅ No manual intervention required

---

### Quick Verification Commands

Here's a quick reference for common verification tasks:

```bash
# Check if ErsatzTV is running
curl http://localhost:8409/api/channels

# Check ChannelFlow health
curl http://localhost:3000/health

# View recent sync status
curl http://localhost:3000/api/channels/horror-night/sync-status

# Trigger manual sync
curl -X POST http://localhost:3000/api/channels/horror-night/sync?backend=ersatztv

# Generate and save YAML (via API)
curl http://localhost:3000/api/channels/horror-night/export/ersatztv-yaml > /tmp/schedule.yaml

# Test stream playback
vlc http://localhost:8409/iptv/channel/100.m3u8

# View EPG
curl http://localhost:8409/iptv/xmltv.xml | grep "Horror Night"
```

```clojure
;; Quick REPL checks
(require '[channelflow.system :as sys])
(def system (component/start (sys/create-system)))

;; Check catalog
(count (media/list-all (:catalog system)))

;; Check channels
(ch/list-channels (:catalog system))

;; Check last sync
(sync/get-sync-status (:catalog system) :horror-night)

;; Force sync
(sync/sync-channel-to-ersatztv! 
  (get-in system [:backends :ersatztv :client])
  (:catalog system)
  :horror-night)
```

---

## Appendix: References

### ErsatzTV Resources
- **Documentation:** https://ersatztv.org/docs/
- **GitHub:** https://github.com/ErsatzTV/ErsatzTV
- **Sequential Schedules:** https://ersatztv.org/docs/scheduling/sequential/
- **Scripted Schedules:** https://ersatztv.org/docs/scheduling/scripted/
- **API:** Available at `http://localhost:8409/swagger` when running

### Tunarr Resources
- **Documentation:** https://tunarr.com/
- **GitHub:** https://github.com/chrisbenincasa/tunarr
- **Feature Request (Dynamic Channels):** https://github.com/chrisbenincasa/tunarr/issues/15

---

**Last Updated:** 2026-01-22  
**Status:** Planning Complete - Ready for Implementation
