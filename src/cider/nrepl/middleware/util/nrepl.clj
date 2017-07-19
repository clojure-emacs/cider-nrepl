(ns cider.nrepl.middleware.util.nrepl
  "Common utilities for interaction with the client."
  (:require
   [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
   [clojure.tools.nrepl.misc :refer [response-for]]
   [clojure.tools.nrepl.transport :as transport]))

(defn notify-client
  "Send user level notification to client as a response to request `msg`.
  If transport is not provided use (:transport msg). If msg is not provided, use
  current *msg* from interruptible-eval middleware. Type is a keyword or string
  indicating type of the message (e.g. :message, :warning, :error etc). Type
  defaults to :message. See `nrepl-notify` on the Emacs side."
  ([notification] (notify-client *msg* notification))
  ([msg notification] (notify-client (:transport msg) msg notification nil))
  ([msg notification type] (notify-client (:transport msg) msg notification type))
  ([tr msg notification type]
   (transport/send tr (apply response-for msg
                             :status :notification
                             :msg notification
                             (when type [:type type])))))
