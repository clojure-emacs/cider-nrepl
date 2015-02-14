(ns cider.nrepl.middleware.pprint
  (:require [clojure.pprint :refer [pprint *print-right-margin*]]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [cider.nrepl.middleware.util.cljs :as cljs]))

(defn pprint-eval
  [form]
  (let [result (eval form)]
    (pprint result)
    result))

(defn wrap-pprint-reply
  [handler {:keys [right-margin session] :as msg}]
  (when right-margin
    (swap! session assoc #'*print-right-margin* right-margin))
  (handler (assoc msg :eval 'cider.nrepl.middleware.pprint/pprint-eval)))

(defn wrap-pprint
  [handler]
  "Middleware that adds a pretty printing option to the eval op.
  Passing a non-nil value in the `:pprint` slot will cause eval to call
  clojure.pprint/pprint on its result. The `:right-margin` slot can be used to
  bind `*clojure.pprint/*print-right-margin*` during the evaluation."
  (fn [{:keys [op pprint right-margin] :as msg}]
    (if (and pprint (= op "eval"))
      (wrap-pprint-reply handler msg)
      (handler msg))))

(set-descriptor!
 #'wrap-pprint
 (cljs/maybe-piggieback
  {:requires #{"clone"}
   :expects #{"eval"}}))
