(ns tunarr.scheduler.http.api.strategy
  "HTTP handlers for strategy management.

   Provides a REST API for viewing, applying, and generating scheduling
   strategies.  Marquee (web frontend) and Hermes consume these endpoints.

   Strategies are persisted via the SQL executor, obtained from the catalog
   component (ctx :catalog → :executor)."

  (:require [taoensso.timbre :as log]
            [tunarr.scheduler.scheduling.strategy :as strategy]
            [tunarr.scheduler.http.util :as util]))

(defn- executor-of
  "Pull the SQL executor out of the catalog component in the handler context."
  [ctx]
  (:executor (:catalog ctx)))

(defn list-strategies-handler
  "GET /api/strategies — list all strategies newest-first.

   Optional query params :period (:monthly|:quarterly) and
   :status (:draft|:applied|:rejected|:reverted) narrow the result."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [{:keys [period status]} (get-in req [:parameters :query])
              strategies (cond->> (strategy/list-strategies executor)
                           period (filter #(= (:period %) period))
                           status (filter #(= (:status %) status)))]
          {:status 200
           :body {:strategies (vec strategies)}})
        (catch Exception e
          (log/error e "Error listing strategies")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn get-strategy-handler
  "GET /api/strategies/:id — get a single strategy by ID."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [id (get-in req [:parameters :path :id])]
          (if-let [s (strategy/get-strategy executor id)]
            {:status 200 :body s}
            {:status 404 :body {:error "Strategy not found"}}))
        (catch Exception e
          (log/error e "Error getting strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn get-current-strategy-handler
  "GET /api/strategies/current — get the most recent strategy.

   With an optional :period query param, returns the most recent strategy
   for that period instead of the most recent across all periods."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [period (get-in req [:parameters :query :period])
              s (if period
                  (strategy/current-strategy-by-period executor period)
                  (strategy/current-strategy executor))]
          (if s
            {:status 200 :body s}
            {:status 404 :body {:error "No strategies yet"}}))
        (catch Exception e
          (log/error e "Error getting current strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn apply-strategy-handler
  "POST /api/strategies/:id/apply — mark a strategy as applied."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [id (get-in req [:parameters :path :id])]
          (if-let [s (strategy/apply-strategy! executor id)]
            {:status 200 :body s}
            {:status 404 :body {:error "Strategy not found"}}))
        (catch Exception e
          (log/error e "Error applying strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn reject-strategy-handler
  "POST /api/strategies/:id/reject — mark a strategy as rejected."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [id (get-in req [:parameters :path :id])]
          (if-let [s (strategy/reject-strategy! executor id)]
            {:status 200 :body s}
            {:status 404 :body {:error "Strategy not found"}}))
        (catch Exception e
          (log/error e "Error rejecting strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn delete-strategy-handler
  "DELETE /api/strategies/:id — delete a strategy."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [id (get-in req [:parameters :path :id])]
          (if-let [s (strategy/delete-strategy! executor id)]
            {:status 200 :body s}
            {:status 404 :body {:error "Strategy not found"}}))
        (catch Exception e
          (log/error e "Error deleting strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn generate-strategy-handler
  "POST /api/strategies — manually trigger strategy generation.

   Body:
     {:period :monthly|:quarterly}"
  [{:keys [llm channels] :as ctx}]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [period (get-in req [:parameters :body :period] :monthly)
              valid-periods #{:monthly :quarterly}]
          (if (valid-periods period)
            (let [s (strategy/generate-strategy! executor llm channels period)]
              {:status 201 :body s})
            {:status 400 :body {:error (str "Invalid period: " period ". Expected :monthly or :quarterly")}}))
        (catch Exception e
          (log/error e "Error generating strategy")
          {:status 500 :body {:error (util/error-message e)}})))))

(defn revert-strategy-handler
  "POST /api/strategies/:id/revert — undo a strategy and restore the previous one.

   This is the escape hatch when an auto-committed strategy is bad."
  [ctx]
  (let [executor (executor-of ctx)]
    (fn [req]
      (try
        (let [id (get-in req [:parameters :path :id])]
          (if-let [result (strategy/revert-strategy! executor id)]
            {:status 200 :body result}
            {:status 404 :body {:error "Strategy not found"}}))
        (catch Exception e
          (log/error e "Error reverting strategy")
          {:status 500 :body {:error (util/error-message e)}})))))
