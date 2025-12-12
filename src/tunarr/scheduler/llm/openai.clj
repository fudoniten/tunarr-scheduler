(ns tunarr.scheduler.llm.openai
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [clojure.spec.alpha :as s]
   [taoensso.timbre :as log]
   [tunarr.scheduler.llm :as llm]))

(defn- ensure-spec
  [spec x]
  (let [cx (s/conform spec x)]
    (if (s/invalid? cx)
      (throw (ex-info (str "value does not conform to spec: " spec)
                      (assoc (s/explain-data spec x)
                             :type :spec-failure
                             :spec spec
                             :value x)))
      cx)))

(defn- sanitize-url [endpoint]
  (when endpoint
    (str/replace endpoint #"/+$" "")))

(defn- ->ex [message response]
  (ex-info message
           (cond-> {:status (:status response)}
             (:body response) (assoc :body (:body response)))))

(defn- ->json [s]
  (try
    (when (seq (str/trim (or s "")))
      (json/parse-string s true))
    (catch Exception e
      (log/warn e "Failed to parse JSON response" {:body s})
      nil)))

(defn- response-content [body]
  (or (-> body :choices first :message :content)
      (when-let [tool (-> body :choices first :message :tool_calls first :function :arguments)]
        tool)))

(defn- structured-response-format? [response-format]
  (cond
    (nil? response-format) false
    (keyword? response-format) (= :json response-format)
    (string? response-format) (= "json" response-format)
    (map? response-format) (contains? #{"json_schema" "json_object"} (:type response-format))
    :else false))

(defn- ensure-json-word [s]
  (if (re-find #"(?i)\\bjson\\b" s)
    s
    (str s "\n\nPlease respond with JSON.")))

(defn- ensure-json-instructions [input]
  (cond
    (string? input) (ensure-json-word input)
    (and (map? input) (string? (:input input))) (update input :input ensure-json-word)
    (sequential? input)
    (let [messages (vec input)
          idx (or (->> (reverse (map-indexed vector messages))
                       (some (fn [[i {:keys [role content]}]]
                               (when (and (= role "user") (string? content)) i))))
                   (when (string? (:content (last messages))) (dec (count messages))))]
      (if idx
        (update messages idx update :content ensure-json-word)
        messages))
    :else input))

(defn- extract-text [body]
  (-> body :output first :content first :text))

(defn- request-openai!
  [{:keys [endpoint api-key model http-opts]} request {:keys [response-format] :as options}]
  (let [url (str (or (sanitize-url endpoint) "https://api.openai.com/v1") "/responses")
        text-config (when response-format {:text {:format response-format}})
        payload (merge {:model model
                        :input request}
                       text-config
                       (dissoc options :response-format))
        request-opts (merge {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :accept :json
                             :as :text
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            http-opts)
        {:keys [status body] :as resp} (http/post url request-opts)]
    (if (<= 200 status 299)
      (json/parse-string body true)
      (throw (->ex "OpenAI request failed" resp)))))

(defn- ensure-api-key! [api-key]
  (when (str/blank? api-key)
    (throw (ex-info "OpenAI API key is required" {})))
  api-key)

(defn- decode-json-response [body response-format]
  (let [content (some-> body extract-text)]
    (if (and content (structured-response-format? response-format))
      (or (->json content) {})
      (or (->json content) {}))))

(defn openai-request-arbitrary-json!
  ([client instructions]
   (openai-request-arbitrary-json! client instructions {}))
  ([client instructions options]
   (let [response-format {:type "json_object"
                          :json_object {:name "response"}}
         body (request-openai! client (ensure-json-instructions instructions)
                               (merge {:temperature 0.2
                                       :response-format response-format}
                                      options))]
     (decode-json-response body response-format))))

(defn openai-request-json!
  "Request a JSON response validated by the provided JSON schema.

  `schema` should be a JSON Schema map. Optionally provide `:schema-name` in
  the options map to control the `name` sent alongside the schema."
  ([client instructions schema]
   (openai-request-json! client instructions schema {}))
  ([client instructions schema {:keys [schema-name] :or {schema-name "response"} :as options}]
   (let [response-format {:type "json_schema"
                          :json_schema {:name schema-name
                                        :schema schema}
                          :strict true}
         body (request-openai! client (ensure-json-instructions instructions)
                               (merge {:temperature 0.2
                                       :response-format response-format}
                                      (dissoc options :schema-name)))]
     (decode-json-response body response-format))))

(defrecord OpenAIClient [model endpoint api-key http-opts]
  llm/LLMClient
  (request! [client {:keys [schema instructions] :as req}]
    (cond
      schema (openai-request-json! client instructions schema (dissoc req :schema :instructions))
      :else (openai-request-arbitrary-json! client instructions (dissoc req :instructions))))
  (close! [_]
    (log/info "closing connection to OpenAI")
    nil))

(defmethod llm/create! :openai
  [{:keys [api-key endpoint model http-opts]}]
  (ensure-api-key! api-key)
  (let [client (->OpenAIClient (or model "gpt-4o-mini")
                               (or endpoint "https://api.openai.com/v1")
                               api-key
                               (or http-opts {}))
        intro-schema {:type "object"
                      :name "introduction"
                      :properties {:message {:type "string"}}
                      :required ["message"],
                      :additionalProperties false}]
    (log/info "Initialised OpenAI client" (dissoc (into {} client) :api-key))
    (log/info "OpenAI says: %s"
              (openai-request-json! client
                                    "Please introduce yourself in one sentence."
                                    intro-schema
                                    {:schema-name "introduction"}))
    client))
