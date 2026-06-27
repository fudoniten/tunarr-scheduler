(ns tunarr.scheduler.http.util
  "Common utilities for HTTP handlers.")

(defn error-message
  "Safely extract an error message from an exception, providing a fallback if nil."
  [^Exception e]
  (or (.getMessage e)
      (str (.getSimpleName (class e)))))
