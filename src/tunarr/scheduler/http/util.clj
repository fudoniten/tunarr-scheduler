(ns tunarr.scheduler.http.util
  "Common utilities for HTTP handlers.")

(defn error-message
  "Safely extract an error message from a throwable, providing a fallback if nil."
  [^Throwable e]
  (or (.getMessage e)
      (str (.getSimpleName (class e)))))
