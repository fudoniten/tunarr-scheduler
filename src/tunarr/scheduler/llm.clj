(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [taoensso.timbre :as log]))

(defmulti create-client
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defn- sanitize-url [endpoint]
  (when endpoint
    (str/replace endpoint #"/+$" "")))

(defmethod create-client :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (assoc config :type :generic))

(defmethod create-client :mock [_]
  {:type :mock})

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

(defmethod create-client :openai
  [{:keys [api-key endpoint model http-opts] :as config}]
  (ensure-api-key! api-key)
  (let [client {:type :openai
                :model (or model "gpt-4o-mini")
                :endpoint (or endpoint "https://api.openai.com/v1")
                :api-key api-key
                :http-opts (or http-opts {})}]
    (log/info "Initialised OpenAI client" (dissoc client :api-key))
    client))

(defmulti close!
  "Clean up any LLM client resources."
  (fn [client] (:type client)))

(defmethod close! :default [client]
  (log/info "Closing LLM client" {:type (:type client)})
  (when-let [close-fn (:close client)]
    (close-fn)))

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

(defn classify-media!
  "Classify a media entity by delegating to the configured LLM."
  [{:keys [type] :as client} media]
  (log/info "Classifying media" {:title (:name media) :type type})
  (case type
    :openai (openai-classify-media client media)
    {:tags #{"unspecified"}
     :channels [:general]
     :kid-friendly? false}))

(defn schedule-programming!
  "Generate a schedule via the LLM. Placeholder implementation." [client request]
  (log/info "Scheduling programming via LLM" {:request request :type (:type client)})
  (case (:type client)
    :openai (let [prompt (str "Create a JSON schedule with a 'slots' array. Each slot should have "
                              "'start' (ISO8601 timestamp), 'end', 'title', and 'tags' (array). "
                              "Request context: " (pr-str request))
                   response (openai-json-response client
                                                  [{:role "system"
                                                    :content "You are a TV channel scheduler."}
                                                   {:role "user" :content prompt}])]
              (if (contains? response :slots)
                {:slots (:slots response)}
                {:slots []}))
    {:slots []}))

(defn generate-bumper-script
  "Generate narration script for bumpers using the LLM."
  [client {:keys [type channel upcoming]}]
  (log/info "Generating bumper script" {:channel channel :type type})
  (case type
    :openai (let [messages [{:role "system"
                             :content "You write concise, energetic TV bumper narration."}
                            {:role "user"
                             :content (str "Write a single-sentence bumper for channel " channel
                                           " introducing " (or upcoming "our upcoming program") ".")}] 
                   body (request-openai! client messages {:temperature 0.7})
                   content (response-content body)]
               (or content (format "Up next on %s: %s" channel (or upcoming "More great content!"))))
    (format "Up next on %s: %s" channel (or upcoming "More great content!"))))
