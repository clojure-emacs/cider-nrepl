(ns hooks.core
  (:require
   [clj-kondo.hooks-api :as api]))

(defn with-out-binding
  "Rewrite with-out-binding as a let on the first symbol in the arg vector.
  The remaining args in the  arg vector are evaluated."
  [{:keys [node]}]
  (let [[_ binding-vec & body] (:children node)
        [binding & others] (:children binding-vec)
        new-node (api/list-node
                  (list*
                   (api/token-node 'let)
                   (api/vector-node
                    (reduce
                     into
                     [binding (api/token-node 'nil)]
                     (mapv
                      #(vector (api/token-node '_) %)
                      others)))
                   body))]
    ;; un-comment below to debug changes
    ;; (prn :with-binding (api/sexpr new-node))
    {:node (with-meta new-node (meta node))}))
