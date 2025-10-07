(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require
   [taoensso.timbre :as log]))

;; TODO: Flesh out what these requests should look like.
;;
;; Thoughts:
;;  * To classify media, we need to provide a list of existing tags, and
;;    channels to which the media should be mapped. The channels should
;;    be provided by config, whereas tags can be generated as necessary
;;    (but with maximal overlap)
;;  * Scheduling media probably needs more detail. There should be a
;;    'recurring' schedule, with the same shows appearing in the same
;;    slots on the same day week after week. The rest of the time, there
;;    should be a choice between 'flex' times, and specific themed
;;    scheduling ("Spy Saturday", "Futurama Marathon", "80s Fantasy Day").
;;    It feels like this could be two-pass: first, based on available
;;    media, brainstorm ideas and 'themes'. Then, flesh out a concrete
;;    schedule.
;;  * Bumpers will need scheduling info for the next, say, 6 hours. Also,
;;    potentially, the "big ideas" from above (the theme weeks, marathons,
;;    etc), so they can be 'advertised'. Finally, random cross-channel ads
;;    to make users aware of what's going on on other channels. There should
;;    be per-channel collections of backgrounds & bumper graphics, music,
;;    and voices, for use in generating the bumper. Not sure about how to
;;    add text...

(defprotocol LLMClient
  "Protocol describing the interactions supported by LLM providers."
  (classify-media! [client media channels existing-tags]
    "Classify a media entity by delegating to the configured LLM.")
  (schedule-programming! [client request]
    "Generate a schedule via the LLM. Placeholder implementation.")
  (generate-bumper-script [client request]
    "Generate narration script for bumpers using the LLM.")
  (close! [client]
    "Clean up any LLM client resources."))

(defmulti create-client
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defrecord GenericLLMClient [provider close-fn]
  LLMClient
  (classify-media! [_ media channels existing-tags]
    (log/info "Classifying media" {:title (:name media) :type :generic})
    {:tags #{"unspecified"}
     :channels [:general]
     :kid-friendly? false})
  (schedule-programming! [_ request]
    (log/info "Scheduling programming via LLM" {:request request :type :generic})
    {:slots []})
  (generate-bumper-script [_ {:keys [channel upcoming]}]
    (log/info "Generating bumper script" {:channel channel :type :generic})
    (format "Up next on %s: %s" channel (or upcoming "More great content!")))
  (close! [_]
    (log/info "Closing LLM client" {:type :generic :provider provider})
    (when close-fn
      (close-fn))))

(defmethod create-client :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (->GenericLLMClient (:provider config) (:close config)))

(defmethod create-client :mock [_]
  (->GenericLLMClient :mock nil))
