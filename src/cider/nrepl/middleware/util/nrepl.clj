(ns cider.nrepl.middleware.util.nrepl
  "Common utilities for interaction with the client."
  (:require
   [clojure.tools.nrepl.middleware.interruptible-eval :refer [*msg*]]
   [clojure.tools.nrepl.misc :refer [response-for]]
   [clojure.tools.nrepl.transport :as transport]))

(defn send-user-message
  "Send user level message to client as a response to request `msg`.
  If transport is not provided use (:transport msg). If msg is not provided, use
  current *msg* from interruptible-eval middleware. Type is a keyword or string
  indicating type of the message (e.g. :warning, :debug etc). "
  ([message] (send-user-message *msg* message))
  ([msg message] (send-user-message (:transport msg) msg message nil))
  ([msg message type] (send-user-message (:transport msg) msg message type))
  ([tr msg message type]
   (transport/send tr (apply response-for msg :status :message :msg message (when type [:type type])))))

