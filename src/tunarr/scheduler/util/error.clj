(ns tunarr.scheduler.util.error
  (:require [clojure.stacktrace :refer [print-cause-trace]]))

(defn capture-stack-trace
  "Capture the stack trace of an exception as a string.

   Uses `clojure.stacktrace/print-cause-trace`, which (unlike the more
   commonly used `print-stack-trace`) walks the cause chain and renders
   each frame with a 'Caused by:' marker — so a wrapped exception such as
   `(RuntimeException. \"wrapper\" (IllegalArgumentException. ...))`
   surfaces both the outer and inner exception names+messages in the
   returned string."
  [e]
  (with-out-str (print-cause-trace e)))
