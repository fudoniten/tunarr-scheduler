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

(defn- request-openai!
  [{:keys [endpoint api-key model http-opts]} messages {:keys [response-format] :as options}]
  (let [url (str (or (sanitize-url endpoint) "https://api.openai.com/v1") "/chat/completions")
        payload (merge {:model model
                        :messages messages}
                       (when response-format
                         {:response_format response-format})
                       (dissoc options :response-format))
        request-opts (merge {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :accept :json
                             :as :text
                             :throw-exceptions false
                             :body (json/generate-string payload)}
                            http-opts)
        response (http/post url request-opts)]
    (if (<= 200 (:status response) 299)
      (some-> response :body (json/parse-string true))
      (throw (->ex "OpenAI request failed" response)))))

(defn- ensure-api-key! [api-key]
  (when (str/blank? api-key)
    (throw (ex-info "OpenAI API key is required" {})))
  api-key)

(defn- openai-json-request! [client messages]
  (let [body (request-openai! client messages {:temperature 0.2
                                               :response-format {:type "json_object"}})
        content (some-> body response-content)]
    (or (->json content) {})))

(defrecord OpenAIClient [model endpoint api-key http-opts]
  llm/LLMClient
  (request! [client req]
    (openai-json-request! client req))
  (close! [_]
    (log/info "closing connection to OpenAI")
    nil))

(defmethod llm/create-client :openai
  [{:keys [api-key endpoint model http-opts]}]
  (ensure-api-key! api-key)
  (let [client (->OpenAIClient (or model "gpt-4o-mini")
                               (or endpoint "https://api.openai.com/v1")
                               api-key
                               (or http-opts {}))]
    (log/info "Initialised OpenAI client" (dissoc (into {} client) :api-key))
    client))
