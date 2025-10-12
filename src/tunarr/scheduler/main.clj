(ns tunarr.scheduler.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
;;            [integrant.core :as ig]
            [taoensso.timbre :as log]
            [tunarr.scheduler.config :as config]
            [tunarr.scheduler.system :as system]))

(def cli-options
  [["-c" "--config PATH" "Path to configuration EDN file"]
   [nil "--no-block" "Do not block the main thread after starting the service."]
   ["-h" "--help"]])

(defn- usage [options-summary]
  (->> ["Tunarr Scheduler"
        ""
        "Usage: scheduler [options]"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      (do (println (usage summary))
          (System/exit 0))

      (seq errors)
      (do (binding [*out* *err*]
            (println "Error parsing command line options:")
            (doseq [err errors] (println "  " err))
            (println)
            (println (usage summary)))
          (System/exit 1))

      :else
      (let [config-map (config/load-config (:config options))
            system-config (config/config->system config-map)
            system (system/start system-config)]
        (log/info "Tunarr scheduler started" {:port (get-in config-map [:server :port])})
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (log/info "Shutdown requested")
                                     (system/stop system))))
        (when-not (:no-block options)
          (log/info "Blocking main thread; press Ctrl+C to exit")
          (deref (promise)))))))
