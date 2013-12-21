(ns cider.nrepl.middleware.doc
  (:require
   [clojure.tools.nrepl.transport :as transport])
  (:use
   [clojure.repl :only [doc]]
   [clojure.tools.nrepl.middleware :only [set-descriptor!]]
   [clojure.tools.nrepl.misc :only [response-for]]))

(defn get-doc
  "Get documentation for sym."
  [sym]
  (let [doc (with-out-str (eval `(doc ~sym)))]
    (if (= doc "") nil doc)))

(defn doc-response
  "Response for the :doc message."
  [{:keys [symbol ns transport] :as msg}]
  (if-let [doc (get-doc (clojure.core/symbol symbol))]
    (do (transport/send transport (response-for msg :doc doc))
        (transport/send transport (response-for msg :status :done)))
    (transport/send transport (response-for msg :status :not-found))))

(defn docstring-response
  "Response for the :docstring message."
  [{:keys [symbol ns transport] :as msg}]
  (if-let [v (ns-resolve (clojure.core/symbol ns) (clojure.core/symbol symbol))]
    (do (transport/send transport (response-for msg :docstring (-> v meta :doc)))
        (transport/send transport (response-for msg :status :done)))
    (transport/send transport (response-for msg :status :not-found))))

(defn wrap-doc
  "Middleware that looks up doc for a symbol."
  [handler]
  (fn [{:keys [op] :as msg}]
    (case op
      "doc" (doc-response msg)
      "docstring" (docstring-response msg)
      (handler msg))))

(set-descriptor!
 #'wrap-doc
 {:handles
  {"doc"
   {:doc "Return doc for the specified symbol & ns combo."
    :requires {"symbol" "The symbol to lookup"}
    :returns {"status" "done"}}}})
