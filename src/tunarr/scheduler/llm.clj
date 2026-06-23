(ns tunarr.scheduler.llm
  "Generic LLM client for scheduling intents and other non-media tasks.

   Supports OpenAI-compatible endpoints and Ollama.  Falls back to a
   mock/echo mode when no API key is configured."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [taoensso.timbre :as log]))

;; ---------------------------------------------------------------------------
;; OpenAI-compatible chat completions
;; ---------------------------------------------------------------------------

(defn- chat-completion-openai!
  "Call an OpenAI-compatible chat completions endpoint.

   Args:
     endpoint — Base URL (e.g. 'https://api.openai.com/v1')
     api-key  — Bearer token
     model    — Model name (e.g. 'gpt-4o')
     messages — Vector of {:role 'system'|'user'|'assistant' :content string}
     opts     — Optional: {:temperature 0.2 :max-tokens 2048}

   Returns:
     {:content string :model string :usage map}"
  [{:keys [endpoint api-key model messages opts]}]
  (let [url (str (or endpoint "https://api.openai.com/v1") "/chat/completions")
        request-opts {:accept :json
                      :as :json
                      :throw-exceptions false
                      :headers {"Content-Type" "application/json"
                                "Authorization" (str "Bearer " api-key)}
                      :body (json/generate-string
                             (merge {:model (or model "gpt-4o")
                                     :messages messages
                                     :temperature 0.2
                                     :max_tokens 2048
                                     :response_format {:type "json_object"}}
                                    opts))}]
    (log/debug "LLM request" {:url url :model model :messages (count messages)})
    (let [{:keys [status body]} (http/post url request-opts)]
      (if (<= 200 status 299)
        (let [choice (first (get-in body [:choices]))
              content (get-in choice [:message :content])]
          {:content content
           :model (get body :model model)
           :usage (get body :usage)})
        (do
          (log/error "LLM request failed" {:status status :body body})
          (throw (ex-info (format "LLM request failed: %s" status)
                          {:status status :body body})))))))

;; ---------------------------------------------------------------------------
;; Ollama generate
;; ---------------------------------------------------------------------------

(defn- chat-completion-ollama!
  "Call an Ollama /api/generate endpoint.

   Args:
     endpoint — Base URL (e.g. 'http://localhost:11434')
     model    — Model name (e.g. 'llama3.1')
     prompt   — Full prompt string
     opts     — Optional: {:temperature 0.2}

   Returns:
     {:content string :model string}"
  [{:keys [endpoint model prompt opts]}]
  (let [url (str (or endpoint "http://localhost:11434") "/api/generate")
        request-opts {:accept :json
                      :as :json
                      :throw-exceptions false
                      :headers {"Content-Type" "application/json"}
                      :body (json/generate-string
                             (merge {:model (or model "llama3.1")
                                     :prompt prompt
                                     :stream false
                                     :format "json"
                                     :options {:temperature (get opts :temperature 0.2)}}
                                    (select-keys opts [:context])))}]
    (log/debug "Ollama request" {:url url :model model})
    (let [{:keys [status body]} (http/post url request-opts)]
      (if (<= 200 status 299)
        {:content (get body :response)
         :model (get body :model model)}
        (do
          (log/error "Ollama request failed" {:status status :body body})
          (throw (ex-info (format "Ollama request failed: %s" status)
                          {:status status :body body})))))))

;; ---------------------------------------------------------------------------
;; Mock / echo mode
;; ---------------------------------------------------------------------------

(defn- mock-completion!
  "Return a mock response for testing without an LLM.

   Parses the prompt to detect simple operations and returns a canned
   JSON response.  This is useful for testing the intent pipeline end-to-end
   without consuming API credits."
  [{:keys [prompt]}]
  (log/warn "LLM in mock mode — returning canned response")
  ;; Very naive keyword matching for demo purposes
  (let [response (cond
                   (re-find #"(?i)cheers" prompt)
                   {:reasoning "Detected 'Cheers' in the instruction."
                    :operations [{:type "update_slot"
                                   :slot_index 1
                                   :changes {:required_tags ["cheers"]
                                             :playback_order "season_episode"}}]
                    :preview {:affected_blocks ["Tue/Thu 18:00"]
                             :description "Now plays Cheers sequentially"}}

                   (re-find #"(?i)detective" prompt)
                   {:reasoning "Detected 'detective' theme request."
                    :operations [{:type "update_slot"
                                   :slot_index 1
                                   :changes {:required_tags ["detective" "mystery"]
                                             :playback_order "shuffle"}}]
                    :preview {:affected_blocks ["Primetime block"]
                             :description "Detective-themed content"}}

                   (re-find #"(?i)shuffle" prompt)
                   {:reasoning "Detected shuffle request."
                    :operations [{:type "shuffle_slot"
                                   :slot_index 0}]
                    :preview {:affected_blocks ["All background blocks"]
                             :description "Playback order set to shuffle"}}

                   :else
                   {:reasoning "Could not determine specific operation from instruction."
                    :operations []
                    :preview {:affected_blocks []
                             :description "No changes made"}})]
    {:content (json/generate-string response)
     :model "mock"
     :usage {:prompt_tokens 0 :completion_tokens 0}}))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn chat-completion!
  "Dispatch to the appropriate LLM backend based on config.

   Config keys:
     :provider   — :openai, :ollama, or :mock
     :endpoint   — Base URL (optional for OpenAI)
     :api-key    — API key (required for OpenAI)
     :model      — Model name
     :opts       — Provider-specific options

   For :openai, messages is a vector of {:role :content} maps.
   For :ollama, messages is a single string prompt (concatenated).
   For :mock, returns a canned response."
  [{:keys [provider] :as config} messages]
  (case provider
    :openai (chat-completion-openai! (assoc config :messages messages))
    :ollama (chat-completion-ollama! (assoc config :prompt
                                          (str/join "\n\n"
                                                    (map #(str (name (:role %)) ": " (:content %)) messages))))
    :mock (mock-completion! {:prompt (str/join "\n\n"
                                                 (map #(str (name (:role %)) ": " (:content %)) messages))})
    (do (log/warn "Unknown LLM provider" {:provider provider})
        (mock-completion! {:prompt ""}))))

(defn create!
  "Create an LLM client from config map."
  [{:keys [provider] :as config}]
  (log/info "LLM client initialised" {:provider provider})
  config)
