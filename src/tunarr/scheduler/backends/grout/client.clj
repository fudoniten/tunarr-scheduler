(ns tunarr.scheduler.backends.grout.client
  "Grout API client — filler-content store for Pseudovision.

   Grout is a special-purpose, content-addressed media store: media bytes go in,
   tags (channel, audience, source, …) get attached, and Pseudovision queries it
   to fill scheduling gaps. This client implements the Tunarr Scheduler *write
   path*: after a bumper is composed onto the shared mount, we hand Grout the
   path and it hashes → ffprobes → normalises → stores → indexes.

   Wire conventions (see GROUT.md):
   - All media routes live under `/grout`; JSON in and out.
   - Response bodies use kebab-case keys (`duration-ms`, `content-hash`,
     `stream-url`, …). We pin to those.
   - Intake is idempotent by SHA-256 of the *source* bytes: re-posting the same
     file returns 200 (matched existing) instead of 201 (newly stored), never a
     duplicate."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.security MessageDigest]))

;; ---------------------------------------------------------------------------
;; HTTP plumbing
;; ---------------------------------------------------------------------------

(defn- api-url [base-url path]
  (str (str/replace (or base-url "") #"/$" "") path))

(defn- request!
  "Make a request against Grout. Returns {:status int :body parsed-json}.
   Never throws on a non-2xx status — callers inspect :status so they can
   distinguish 200 (matched) from 201 (created) and handle 4xx/5xx."
  [base-url method path & {:keys [body query-params timeout-ms]}]
  (let [url  (api-url base-url path)
        opts (cond-> {:accept :json
                      :as :json
                      :coerce :always            ;; parse JSON error bodies too
                      :throw-exceptions false}
               query-params (assoc :query-params query-params)
               timeout-ms   (assoc :socket-timeout timeout-ms
                                   :connection-timeout timeout-ms)
               body         (assoc :headers {"Content-Type" "application/json"}
                                   :body (json/generate-string body)))
        response (case method
                   :get  (http/get url opts)
                   :post (http/post url opts))]
    {:status (:status response)
     :body   (:body response)}))

;; ---------------------------------------------------------------------------
;; Source hashing (for the optional by-hash pre-check)
;; ---------------------------------------------------------------------------

(defn sha256-file
  "SHA-256 of a file's bytes as lowercase hex. Grout keys intake idempotency on
   the SHA-256 of the *source* bytes, so this matches its `content-hash`
   (minus the `sha256-` prefix Grout reports)."
  [path]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (io/file path))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n)
              (.update digest buf 0 n)
              (recur))))))
    (str/join (map #(format "%02x" %) (.digest digest)))))

;; ---------------------------------------------------------------------------
;; Intake — the write path
;; ---------------------------------------------------------------------------

(defn intake!
  "Register a media file with Grout (POST /grout/media).

   `params` keys (kebab-case, matching the API body):
     :path        — absolute path on the shared mount, under GROUT_MEDIA_DIR (required)
     :kind        — \"bumper\" | \"filler\" | \"program\" (required)
     :channel     — channel slug, or nil/omitted for generic (usable everywhere)
     :tags        — vector of tag strings (optional)
     :source      — provenance, e.g. \"tunarr-bumper\" (optional)
     :source-url  — provenance URL (optional)
     :name        — display name (optional)
     :description — description (optional)

   Grout hashes → ffprobes → normalises to the playout profile (+faststart) →
   stores content-addressed → inserts. Idempotent by source hash.

   Returns {:created? bool :status int :media <Media>}:
     201 → :created? true  (newly stored)
     200 → :created? false (matched an existing item by hash; tags unioned)
   Throws ex-info on 400 (missing/outside media dir), 422 (pipeline failure),
   or any other non-2xx."
  [{:keys [base-url]} params]
  (let [body (into {} (remove (comp nil? val))
                   (select-keys params
                                [:path :kind :channel :tags
                                 :source :source-url :name :description]))
        {:keys [status body]} (request! base-url :post "/grout/media" :body body)]
    (case (int status)
      (200 201)
      (do (log/info "Grout intake ok"
                    {:id (:id body) :status status
                     :created? (= 201 status) :path (:path params)})
          {:created? (= 201 status) :status status :media body})
      ;; else — surface the error to the caller
      (do (log/error "Grout intake failed"
                     {:status status :path (:path params) :body body})
          (throw (ex-info "Grout intake failed"
                          {:status status :path (:path params) :response body}))))))

(defn by-hash
  "Pre-check by SHA-256 of the source bytes (GET /grout/by-hash/:hash).
   Returns the full Media map on a hit, or nil on 404. Useful to skip an
   upload when the exact bytes already live in Grout; note that freshly
   generated bumpers are always unique, so this mostly helps on re-runs of an
   already-uploaded file."
  [{:keys [base-url]} hash]
  (let [{:keys [status body]} (request! base-url :get (str "/grout/by-hash/" hash))]
    (when (= 200 status) body)))

(defn get-media
  "Fetch a full Media object by id (GET /grout/media/:id). Returns nil on 404."
  [{:keys [base-url]} id]
  (let [{:keys [status body]} (request! base-url :get (str "/grout/media/" id))]
    (when (= 200 status) body)))

;; ---------------------------------------------------------------------------
;; Health
;; ---------------------------------------------------------------------------

(defn health-check
  "Check that Grout is reachable. Returns {:reachable bool ...}."
  [{:keys [base-url]}]
  (try
    (let [{:keys [status body]} (request! base-url :get "/health")]
      {:reachable (= 200 status) :status status :body body})
    (catch Exception e
      (log/error e "Grout health check failed" {:base-url base-url})
      {:reachable false :error (.getMessage e)})))

;; ---------------------------------------------------------------------------
;; Client construction
;; ---------------------------------------------------------------------------

(defn create!
  "Create a Grout client config map.
     :base-url — Grout API base URL (env GROUT_URL overrides), e.g.
                 \"http://grout:8080\".
   Returns nil (integration disabled) when no base URL is configured."
  [{:keys [base-url]}]
  (let [url (or base-url (System/getenv "GROUT_URL"))]
    (if (str/blank? url)
      (do (log/warn "No Grout base URL configured — bumper→Grout upload disabled")
          nil)
      (do (log/info "Grout client configured" {:base-url url})
          {:base-url url}))))
