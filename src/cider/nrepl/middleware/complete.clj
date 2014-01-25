(ns cider.nrepl.middleware.complete
  (:require [clojure.string :as s]
            [clojure.tools.nrepl.transport :as transport]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [complete.core :as jvm-complete]
            [cljs-complete.core :as cljs-complete]))

(defn grab-cljs-env
  [msg]
  (when-let [piggieback-key (resolve 'cemerick.piggieback/*cljs-repl-env*)]
    (let [session (:session msg)
          env (get @session piggieback-key)]
      (if env @(:cljs.env/compiler env)))))

(defn as-sym
  [x]
  (if x (symbol x)))

(defn complete
  [{:keys [symbol ns public-only? case-sensitive? prefer-ns] :as msg}]
  (let [ns (as-sym ns)
        prefix (str symbol)]
    (if-let [cljs-env (grab-cljs-env msg)]
      (cljs-complete/completions cljs-env prefix ns)
      (jvm-complete/completions prefix ns))))

(defn complete-reply
  [{:keys [transport] :as msg}]
  (let [results (complete msg)]
    (transport/send
     transport (response-for msg :value results))
    (transport/send transport (response-for msg :status :done))
    ))

(defn wrap-complete'
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "complete" op)
      (complete-reply msg)
      (handler msg))))

(defn wrap-complete
  "Middleware that looks up possible functions for the given (partial) symbol."
  [handler]
  (wrap-complete' handler))

(defn try-piggieback
  []
  (try
    (require 'cemerick.piggieback)
    (resolve 'cemerick.piggieback/wrap-cljs-repl)
    (catch Exception _)))

(set-descriptor!
 #'wrap-complete
 {:requires (if-let [piggieback (try-piggieback)]
              #{"session" piggieback}
              #{"session"})
  :expects #{"load-file" "eval"}
  :handles
  {"complete"
   {:doc "Return a list of symbols matching the specified (partial) symbol."
    :requires {"symbol" "The symbol to lookup"
               "ns" "The current namespace"}
    :returns {"status" "done"}}}})


