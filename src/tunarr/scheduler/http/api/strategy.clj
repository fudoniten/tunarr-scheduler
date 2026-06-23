(ns tunarr.scheduler.http.api.strategy
  "HTTP handlers for strategy management.

   Provides a REST API for viewing, applying, and generating scheduling
   strategies.  Marquee (web frontend) and Hermes consume these endpoints."

  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.strategy :as strategy]))

(defn list-strategies-handler
  "GET /api/strategies — list all strategies newest-first.

   Optional query params :period (:monthly|:quarterly) and
   :status (:draft|:applied|:rejected|:reverted) narrow the result."
  [_]
  (fn [req]
    (try
      (let [{:keys [period status]} (get-in req [:parameters :query])
            strategies (cond->> (strategy/list-strategies)
                         period (filter #(= (:period %) period))
                         status (filter #(= (:status %) status)))]
        {:status 200
         :body {:strategies (vec strategies)}})
      (catch Exception e
        (log/error e "Error listing strategies")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-strategy-handler
  "GET /api/strategies/:id — get a single strategy by ID."
  [_]
  (fn [req]
    (try
      (let [id (get-in req [:parameters :path :id])]
        (if-let [s (strategy/get-strategy id)]
          {:status 200 :body s}
          {:status 404 :body {:error "Strategy not found"}}))
      (catch Exception e
        (log/error e "Error getting strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn get-current-strategy-handler
  "GET /api/strategies/current — get the most recent strategy.

   With an optional :period query param, returns the most recent strategy
   for that period instead of the most recent across all periods."
  [_]
  (fn [req]
    (try
      (let [period (get-in req [:parameters :query :period])
            s (if period
                (strategy/current-strategy-by-period period)
                (strategy/current-strategy))]
        (if s
          {:status 200 :body s}
          {:status 404 :body {:error "No strategies yet"}}))
      (catch Exception e
        (log/error e "Error getting current strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn apply-strategy-handler
  "POST /api/strategies/:id/apply — mark a strategy as applied."
  [_]
  (fn [req]
    (try
      (let [id (get-in req [:parameters :path :id])]
        (if-let [s (strategy/apply-strategy! id)]
          {:status 200 :body s}
          {:status 404 :body {:error "Strategy not found"}}))
      (catch Exception e
        (log/error e "Error applying strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn reject-strategy-handler
  "POST /api/strategies/:id/reject — mark a strategy as rejected."
  [_]
  (fn [req]
    (try
      (let [id (get-in req [:parameters :path :id])]
        (if-let [s (strategy/reject-strategy! id)]
          {:status 200 :body s}
          {:status 404 :body {:error "Strategy not found"}}))
      (catch Exception e
        (log/error e "Error rejecting strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn delete-strategy-handler
  "DELETE /api/strategies/:id — delete a strategy."
  [_]
  (fn [req]
    (try
      (let [id (get-in req [:parameters :path :id])]
        (if-let [s (strategy/delete-strategy! id)]
          {:status 200 :body s}
          {:status 404 :body {:error "Strategy not found"}}))
      (catch Exception e
        (log/error e "Error deleting strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn generate-strategy-handler
  "POST /api/strategies/generate — manually trigger strategy generation.

   Body:
     {:period :monthly|:quarterly}"
  [{:keys [llm channels]}]
  (fn [req]
    (try
      (let [period (get-in req [:parameters :body :period] :monthly)
            valid-periods #{:monthly :quarterly}]
        (if (valid-periods period)
          (let [s (strategy/generate-strategy! llm channels period)]
            {:status 201 :body s})
          {:status 400 :body {:error (str "Invalid period: " period ". Expected :monthly or :quarterly")}}))
      (catch Exception e
        (log/error e "Error generating strategy")
        {:status 500 :body {:error (.getMessage e)}}))))

(defn revert-strategy-handler
  "POST /api/strategies/:id/revert — undo a strategy and restore the previous one.

   This is the escape hatch when an auto-committed strategy is bad."
  [_]
  (fn [req]
    (try
      (let [id (get-in req [:parameters :path :id])]
        (if-let [result (strategy/revert-strategy! id)]
          {:status 200 :body result}
          {:status 404 :body {:error "Strategy not found"}}))
      (catch Exception e
        (log/error e "Error reverting strategy")
        {:status 500 :body {:error (.getMessage e)}}))))
