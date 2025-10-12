(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require
   [taoensso.timbre :as log]
   [tunarr.scheduler.media :as media]
   [clojure.spec.alpha :as s]))

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
  (classify-media! [client context media-item]
    "Classify a media entity by delegating to the configured LLM.")
  (schedule-programming! [client context catalog]
    "Generate a schedule via the LLM. Placeholder implementation.")
  (generate-schedule-bumper-script [client schedule]
    "Generate narration script for bumper based on upcoming schedule.")
  (generate-preview-bumper-script [client summary]
    "Generate narration script for bumpers for upcoming 'events'.")
  (generate-channel-bumper-script [client channel-schedules]
    "Generate narration script for bumpers for other channels.")
  (close! [client]
    "Perform any required close or teardown operations."))

(defn llm-client? [o] (satisfies? LLMClient o))

(s/def ::llm-client llm-client?)

(s/def ::classify-media-request
  (s/keys :req [::media/tags
                ::media/channel-descriptions
                ::media/media-metadata]))

(s/fdef classify-media!
  :args (s/cat :client llm-client? :request ::classify-media-request)
  :ret  ::media/classification)

(defmulti create-client
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defrecord GenericLLMClient [provider close-fn]
  LLMClient
  (classify-media! [_ context media-item]
    (throw (ex-info "not implemented: classify-media!" {})))
  (schedule-programming! [_ context catalog]
    (throw (ex-info "not implemented: schedule-programming!" {})))
  (generate-schedule-bumper-script [client schedule]
    (throw (ex-info "not implemented: generate-schedule-bumper-script" {})))
  (generate-preview-bumper-script [client summary]
    (throw (ex-info "not implemented: generate-preview-bumper-script" {})))
  (generate-channel-bumper-script [client channel-schedules]
    (throw (ex-info "not implemented: generate-channel-bumper-script" {})))
  (close! [client]
    (throw (ex-info "not implemented: close!" {}))))

(defmethod create-client :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (->GenericLLMClient (:provider config) (:close config)))

(defmethod create-client :mock [_]
  (->GenericLLMClient :mock nil))
