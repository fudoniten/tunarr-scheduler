(ns tunarr.scheduler.llm
  "Abstraction for Large Language Model providers used by the scheduler."
  (:require
   [taoensso.timbre :as log]
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
  (request! [client req]
    "Send a request to the LLM.")
  (close! [client]
    "Perform any required close or teardown operations."))

(defn llm-client? [o] (satisfies? LLMClient o))

(s/def ::llm-client llm-client?)

(defmulti create!
  "Create an LLM client from configuration."
  (fn [{:keys [provider]}] (keyword provider)))

(defrecord GenericLLMClient [provider close-fn]
  LLMClient
  (request! [_ request]
    (throw (ex-info "not implemented: request!" {})))
  (close! [client]
    (throw (ex-info "not implemented: close!" {}))))

(defmethod create! :default [config]
  (log/info "Initialising generic LLM client" {:provider (:provider config)})
  (->GenericLLMClient (:provider config) (:close config)))

(defmethod create! :mock [_]
  (->GenericLLMClient :mock nil))
