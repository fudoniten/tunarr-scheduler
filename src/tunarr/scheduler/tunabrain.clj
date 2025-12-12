(ns tunarr.scheduler.tunabrain
  "HTTP client for interacting with the external tunabrain service."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

(defrecord TunabrainClient [endpoint api-key http-opts]
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
                             :headers (cond-> {"Content-Type" "application/json"}
                                         (:api-key client) (assoc "Authorization" (str "Bearer " (:api-key client))))
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            (:http-opts client))
        {:keys [status body] :as resp} (http/post url request-opts)]
    (if (<= 200 status 299)
      (json/parse-string body true)
      (throw (ex-info (format "tunabrain request failed: %s" status)
                      (cond-> {:status status}
                        body (assoc :body body)))))))

(defn request-tags!
  "Fetch tags for a media item from tunabrain.

  Payload should include the media data and any existing tags so the upstream
  service can deduplicate as needed."
  [client payload]
  (json-post! client "/tags" payload))

(defn request-channel-mapping!
  "Fetch channel mapping metadata for a media item from tunabrain."
  [client payload]
  (json-post! client "/channel-mapping" payload))

(defn create!
  "Create a tunabrain client from configuration.

  Supported keys:
  * `:endpoint` – base URL of the tunabrain proxy service (default
    `http://localhost:8080`).
  * `:api-key` – optional bearer token for authentication.
  * `:http-opts` – additional `clj-http` options."
  [{:keys [endpoint api-key http-opts]}]
  (let [endpoint (or (sanitize-endpoint endpoint) "http://localhost:8080")
        client (->TunabrainClient endpoint api-key (or http-opts {}))]
    (log/info "Initialised tunabrain client" {:endpoint endpoint})
    client))
