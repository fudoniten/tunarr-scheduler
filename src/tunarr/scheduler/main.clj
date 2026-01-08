(ns tunarr.scheduler.main
  (:gen-class)
  (:require [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [tunarr.scheduler.config :as config]
            [tunarr.scheduler.system :as system]))

(def cli-options
  [["-c" "--config PATH" "Path to configuration EDN file"
    :multi true
    :default []
    :update-fn (fnil conj [])
    :missing "at least one config file is required."
    :validate [#(.exists (io/file %)) "config file not found"]]
   ["-h" "--help"]])

(defn- usage [options-summary]
  (->> ["Tunarr Scheduler"
        ""
        "Usage: scheduler [options]"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn deep-merge
  ([] {})
  ([a] a)
  ([a b] (if (and (map? a) (map? b))
           (merge-with deep-merge a b)
           b))
  ([a b & etc] (reduce deep-merge (deep-merge a b) etc)))

(defn merge-configs
  [configs]
  (apply deep-merge
         (map config/load-config
              configs)))

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
      (let [config-map    (merge-configs (:config options))
            system-config (config/config->system config-map)
            system        (system/start system-config)]
        (log/info "Tunarr scheduler started" {:port (get-in config-map [:server :port])})
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (log/info "Shutdown requested")
                                     (system/stop system))))
        (log/info "Blocking main thread; press Ctrl+C to exit")
        (deref (promise))))))
