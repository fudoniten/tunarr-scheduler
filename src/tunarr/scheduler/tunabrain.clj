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

(defn- json-post!
  [^TunabrainClient client path payload]
  (let [url (str (:endpoint client) path)
        request-opts (merge {:accept :json
                             :as :text
                             :headers (cond-> {"Content-Type" "application/json"})
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            (:http-opts client))
        _ (log/info (format "connecting to %s, options: %s"
                            url (with-out-str (pprint request-opts))))
        {:keys [status body]} (http/post url request-opts)]
    (if (<= 200 status 299)
      (json/parse-string body true)
      (throw (ex-info (format "tunabrain request failed: %s" status)
                      (cond-> {:status status}
                        body (assoc :body body)))))))

(defn request-tags!
  "Fetch tags for a media item from tunabrain.

  Payload should include the media data and any existing tags so the upstream
  service can deduplicate as needed."
  [client media & {:keys [catalog-tags]
                   :or   {catalog-tags []}}]
  (if-let [response (json-post! client "/tags"
                                 {:media         media
                                  :existing_tags catalog-tags})]
    (cond
      (s/valid? (s/coll-of string?) response)
      {:tags (mapv keyword response)}

      (map? response)
      (let [{:keys [tags filtered_tags taglines]} response]
        {:tags          (some->> tags (mapv keyword))
         :filtered-tags (some->> filtered_tags (mapv keyword))
         :taglines      taglines})

      :else
      (do (log/error "bad tagging response when categorizing media: %s"
                     (::media/name media))
          (log/debug "media: %s" media)
          (log/debug "response: %s" response)))
    (log/error "no response when requesting media tags for media %s"
               (::media/name media))))

(defn request-categorization!
  "Fetch channel mapping metadata for a media item from tunabrain."
  [client media & {:keys [categories channels]}]
  (if-let [response (json-post! client "/categorize"
                                {:media      media
                                 :channels   channels
                                 :categories categories})]
    (let [{:keys [dimensions mappings]} response]
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
    (log/error "no response when requesting categorization for media %s"
               (::media/name media))))

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
      {:decisions (mapv #(update % :action keyword) decisions)})
    (log/error "no response when requesting tag triage recommendations")))

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
