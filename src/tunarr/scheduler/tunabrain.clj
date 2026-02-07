(ns tunarr.scheduler.tunabrain
  "HTTP client for interacting with the external tunabrain service."
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]
            [clojure.pprint :refer [pprint]]

            [tunarr.scheduler.media :as media]
            
            [cheshire.core :as json]
            [clj-http.client :as http]
            [taoensso.timbre :as log]))

(defrecord TunabrainClient [endpoint http-opts]
  java.io.Closeable
  (close [_]
    (log/info "Closing tunabrain client")))

(defn- sanitize-endpoint [endpoint]
  (some-> endpoint (str/replace #"/+$" "")))

(defn- media-map->tunabrain-format
  "Transform media map from Clojure namespaced keywords to tunabrain's expected format.
   Tunabrain expects plain JSON keys like 'id', 'title', 'overview', etc."
  [media]
  {:id (::media/id media)
   :title (::media/name media)
   :overview (::media/overview media)
   :genres (::media/genres media)
   :tags (mapv name (::media/tags media))
   :production_year (::media/production-year media)
   :premiere_date (str (::media/premiere media))
   :community_rating (::media/community-rating media)
   :critic_rating (::media/critic-rating media)
   :official_rating (::media/rating media)
   :type (name (::media/type media))
   :taglines (::media/taglines media)})

(defn- json-post!
  [^TunabrainClient client path payload]
  (let [url (str (:endpoint client) path)
        request-opts (merge {:accept :json
                             :as :text
                             :headers (cond-> {"Content-Type" "application/json"})
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            (:http-opts client))]
    (log/info (format "connecting to %s, options: %s"
                      url (with-out-str (pprint request-opts))))
    (try
      (let [{:keys [status body]} (http/post url request-opts)]
        (if (<= 200 status 299)
          (json/parse-string body true)
          (let [error-details (try
                               (json/parse-string body true)
                               (catch Exception _
                                 body))]
            (log/error "Tunabrain request failed"
                      {:status status
                       :url url
                       :path path
                       :error-details error-details
                       :request-payload (json/parse-string (json/generate-string payload) true)})
            (throw (ex-info (format "tunabrain request failed: %s - %s" status error-details)
                           {:status status
                            :url url
                            :path path
                            :error-details error-details
                            :request-payload payload})))))
      (catch java.net.ConnectException e
        (throw (ex-info (format "connection refused to tunabrain at %s" url)
                        {:url url :path path :cause :connection-refused}
                        e)))
      (catch java.net.UnknownHostException e
        (throw (ex-info (format "unknown host when connecting to tunabrain at %s" url)
                        {:url url :path path :cause :unknown-host}
                        e)))
      (catch Exception e
        (throw (ex-info (format "error connecting to tunabrain at %s: %s"
                                url (.getMessage e))
                        {:url url :path path}
                        e))))))

(defn request-tags!
  "Fetch tags for a media item from tunabrain.

  Payload should include the media data and any existing tags so the upstream
  service can deduplicate as needed."
  [client media & {:keys [catalog-tags]
                   :or   {catalog-tags []}}]
  (log/debug (format "===== RETAGGING MEDIA:\n%s\n" (with-out-str (pprint media))))
  (if-let [response (json-post! client "/tags"
                                {:media         (media-map->tunabrain-format (remove nil? media))
                                 :existing_tags (mapv name catalog-tags)})]
    (cond
      (s/valid? (s/coll-of string?) response)
      (do
        (log/info (format "Tag response: received %d tags" (count response)))
        {:tags (mapv keyword response)})

      (map? response)
      (let [{:keys [tags filtered_tags taglines]} response]
        (when (nil? tags)
          (throw (ex-info "Invalid tagging response: missing 'tags' key"
                          {:response response
                           :expected-keys [:tags]
                           :media-name (::media/name media)})))
        (log/info (format "Tag response: %d tags, %d filtered"
                          (count tags) (count filtered_tags)))
        {:tags          (mapv keyword tags)
         :filtered-tags (some->> filtered_tags (mapv keyword))
         :taglines      taglines})

      :else
      (throw (ex-info "Unexpected tagging response format"
                      {:response response
                       :media-name (::media/name media)})))
    (throw (ex-info "No response received from tunabrain tagging"
                    {:endpoint (:endpoint client)
                     :media-name (::media/name media)}))))

(defn request-categorization!
  "Fetch channel mapping metadata for a media item from tunabrain."
  [client media & {:keys [categories channels]}]
  (if-let [response (json-post! client "/categorize"
                                {:media      (media-map->tunabrain-format media)
                                 :channels   channels
                                 :categories categories})]
    (let [{:keys [dimensions mappings]} response]
      (when (and (nil? dimensions) (nil? mappings))
        (throw (ex-info "Invalid categorization response: missing 'dimensions' and 'mappings' keys"
                        {:response response
                         :expected-keys [:dimensions :mappings]
                         :media-name (::media/name media)})))
      (log/info (format "Categorization response: %d dimensions, %d mappings"
                        (count dimensions) (count mappings)))
      {:mappings (for [{:keys [channel_name reasons]} mappings]
                   {::media/channel-name (keyword channel_name)
                    ::media/rationale    (str/join "\n" reasons)})
       :dimensions (into {}
                         (map (fn [[category {:keys [dimension values]}]]
                                [(keyword (or dimension category))
                                 (for [{:keys [value reasons]} values]
                                   {::media/category-value (keyword value)
                                    ::media/rationale      (str/join "\n" reasons)})]))
                         dimensions)})
    (throw (ex-info "No response received from tunabrain categorization"
                    {:endpoint (:endpoint client)
                     :media-name (::media/name media)}))))

(defn request-tag-triage!
  "Request governance recommendations for a collection of tags.

  Accepts a list of tag samples (maps with `:tag`, `:usage_count`, and
  `:example_titles`) and optional target limit and debug flags that mirror the
  upstream service."
  [client tag-samples & {:keys [target-limit debug]}]
  (if-let [response (json-post! client "/tag-governance/triage"
                                {:tags         tag-samples
                                 :target_limit target-limit
                                 :debug        debug})]
    (let [{:keys [decisions]} response]
      (when (nil? decisions)
        (throw (ex-info "Invalid tag triage response: missing 'decisions' key"
                        {:response response
                         :expected-keys [:decisions]
                         :tags-count (count tag-samples)})))
      (log/info (format "Tag triage response: %d decisions" (count decisions)))
      {:decisions (mapv #(update % :action keyword) decisions)})
    (throw (ex-info "No response received from tunabrain tag triage"
                    {:endpoint (:endpoint client)
                     :tags-count (count tag-samples)}))))

(defn request-tag-audit!
  "Audit a list of tags for suitability and get removal recommendations.

  Accepts a list of tags (strings or keywords) and returns a list of tags
  recommended for removal with explanations."
  [client tags]
  (if-let [response (json-post! client "/tags/audit"
                                {:tags (mapv name tags)})]
    (let [{:keys [tags_to_delete]} response]
      (when (nil? tags_to_delete)
        (throw (ex-info "Invalid tag audit response: missing 'tags_to_delete' key"
                        {:response response
                         :expected-keys [:tags_to_delete]})))
      (log/info (format "Tag audit response: %d tags recommended for deletion"
                        (count tags_to_delete)))
      {:recommended-for-removal tags_to_delete})
    (throw (ex-info "No response received from tunabrain tag audit"
                    {:endpoint (:endpoint client)
                     :tags-count (count tags)}))))

(defn create!
  "Create a tunabrain client from configuration.

  Supported keys:
  * `:endpoint` – base URL of the tunabrain proxy service (default
    `http://localhost:8080`).
  * `:http-opts` – additional `clj-http` options."
  [{:keys [endpoint http-opts]}]
  (let [endpoint (or (sanitize-endpoint endpoint) "http://localhost:8080")
        client (->TunabrainClient endpoint
                                  (or http-opts {}))]
    (log/info "Initialised tunabrain client" {:endpoint endpoint})
    client))
