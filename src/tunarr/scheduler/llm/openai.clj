(ns tunarr.scheduler.llm.openai
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [tunarr.scheduler.llm :as llm]))

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

(defn- openai-json-response [client messages]
  (let [body (request-openai! client messages {:temperature 0.2
                                               :response-format {:type "json_object"}})
        content (some-> body response-content)]
    (or (->json content) {})))

(defn- openai-classify-media [client media]
  (let [prompt (str "Classify the media item and respond with strict JSON containing the keys "
                    "'tags' (array of lowercase strings), 'channels' (array of lowercase strings), "
                    "and 'kid_friendly' (boolean). Media details: " (pr-str media))
        response (openai-json-response client
                                       [{:role "system"
                                         :content "You are a content scheduler that categorises media for TV channels."}
                                        {:role "user" :content prompt}])
        tags (->> (:tags response)
                  (keep #(when (string? %) (str/lower-case %)))
                  (into #{}))
        channels (->> (:channels response)
                      (keep #(when (string? %) (keyword (str/replace % #"\s+" "-"))))
                      vec)
        kid-friendly? (boolean (or (:kid_friendly response)
                                   (:kid-friendly response)))]
    {:tags (if (seq tags) tags #{"unspecified"})
     :channels (if (seq channels) channels [:general])
     :kid-friendly? kid-friendly?}))

(defrecord OpenAIClient [model endpoint api-key http-opts]
  llm/LLMClient
  (classify-media! [client media]
    (log/info "Classifying media" {:title (:name media) :type :openai})
    (openai-classify-media client media))
  (schedule-programming! [client request]
    (log/info "Scheduling programming via LLM" {:request request :type :openai})
    (let [prompt (str "Create a JSON schedule with a 'slots' array. Each slot should have "
                      "'start' (ISO8601 timestamp), 'end', 'title', and 'tags' (array). "
                      "Request context: " (pr-str request))
          response (openai-json-response client
                                         [{:role "system"
                                           :content "You are a TV channel scheduler."}
                                          {:role "user" :content prompt}])]
      (if (contains? response :slots)
        {:slots (:slots response)}
        {:slots []})))
  (generate-bumper-script [client {:keys [channel upcoming]}]
    (log/info "Generating bumper script" {:channel channel :type :openai})
    (let [messages [{:role "system"
                     :content "You write concise, energetic TV bumper narration."}
                    {:role "user"
                     :content (str "Write a single-sentence bumper for channel " channel
                                   " introducing " (or upcoming "our upcoming program") ".")}]
          body (request-openai! client messages {:temperature 0.7})
          content (response-content body)]
      (or content (format "Up next on %s: %s" channel (or upcoming "More great content!")))))
  (close! [_]
    (log/info "Closing LLM client" {:type :openai})))

(defmethod llm/create-client :openai
  [{:keys [api-key endpoint model http-opts]}]
  (ensure-api-key! api-key)
  (let [client (->OpenAIClient (or model "gpt-4o-mini")
                               (or endpoint "https://api.openai.com/v1")
                               api-key
                               (or http-opts {}))]
    (log/info "Initialised OpenAI client" (dissoc (into {} client) :api-key))
    client))
