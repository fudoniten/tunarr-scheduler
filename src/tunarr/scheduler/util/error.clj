(ns tunarr.scheduler.util.error
  (:require [clojure.stacktrace :refer [print-stack-trace]]))

(defn capture-stack-trace
  "Capture the stack trace of an exception as a string."
  [e]
  (with-out-str (print-stack-trace e)))
