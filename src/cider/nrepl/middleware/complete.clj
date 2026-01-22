(ns cider.nrepl.middleware.complete
  "Code completion middleware.
  Delegates to the compliment library for the heavy lifting.
  Uses clj-suitable for ClojureScript completion."
  (:require
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [compliment.core :as complete]
   [compliment.utils :as complete-utils]
   [orchard.misc :as misc]
   [suitable.compliment.sources.cljs :as suitable-sources]))

(def shadow-cljs-present?
  (try (require 'shadow.cljs.devtools.api) true
       (catch Throwable _ false)))

;; controls if dynamic cljs code completions are active
(def suitable-enabled? true)

(def suitable-complete-for-nrepl
  (when suitable-enabled?
    (requiring-resolve 'suitable.complete-for-nrepl/complete-for-nrepl)))

(def clj-sources
  "A list of Clojure completion sources for compliment."
  [:compliment.sources.vars/vars
   :compliment.sources.namespaces/namespaces
   :compliment.sources.classes/classes
   :compliment.sources.class-members/members
   :compliment.sources.class-members/static-members
   :compliment.sources.keywords/keywords
   :compliment.sources.local-bindings/local-bindings
   :compliment.sources.resources/resources
   :compliment.sources.special-forms/special-forms
   :compliment.sources.special-forms/literals])

(def cljs-sources
  "A list of ClojureScript completion sources for compliment."
  [::suitable-sources/cljs-source
   ;; The local binding analysis done by
   ;; :compliment.sources.local-bindings/local-bindings doesn't perform any
   ;; evaluation or execution of the context form. Thus, it is independent of
   ;; the actual host platform differences. Given that, we can use that same
   ;; source for ClojureScript completion.
   :compliment.sources.local-bindings/local-bindings])

(defn complete
  [{:keys [ns prefix symbol context extra-metadata enhanced-cljs-completion? sort-order]
    :as msg}]
  ;; TODO: Drop legacy symbol param in version 1.0
  (let [prefix (str (or prefix symbol))
        completion-opts {:ns             (misc/as-sym ns)
                         :context        context
                         :sort-order     (or (some-> sort-order keyword) :by-length)
                         :extra-metadata (set (map keyword extra-metadata))}]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      ;; ClojureScript completion
      (binding [suitable-sources/*compiler-env* cljs-env]
        ;; First we get whatever candidates we can from the ClojureScript compiler source
        (cond-> (complete/completions prefix (merge completion-opts {:sources cljs-sources}))
          ;; and we optionally append to them dynamically obtained candidates
          ;; See https://github.com/clojure-emacs/clj-suitable#how-does-it-work for details
          (and suitable-enabled? enhanced-cljs-completion?)
          (concat (suitable-complete-for-nrepl (assoc msg :symbol prefix)))))
      ;; Clojure completion
      (complete/completions prefix (merge completion-opts {:sources clj-sources})))))

(defn completion-doc
  [{:keys [ns sym symbol] :as msg}]
  ;; TODO: Drop legacy symbol param in version 1.0
  (let [sym (str (or sym symbol))
        ns (misc/as-sym ns)]
    (if-let [cljs-env (cljs/grab-cljs-env msg)]
      (binding [suitable-sources/*compiler-env* cljs-env]
        (complete/documentation sym ns {:sources cljs-sources}))
      (complete/documentation sym ns {:sources clj-sources}))))

(defn complete-reply [msg]
  {:completions (complete msg)})

(defn doc-reply
  [msg]
  {:completion-doc (completion-doc msg)})

(defn flush-caches-reply
  [_msg]
  (complete-utils/flush-caches)
  {})

(defn handle-complete [handler msg]
  (with-safe-transport handler msg
    "cider/complete" complete-reply
    "complete" complete-reply
    "cider/complete-doc" doc-reply
    "complete-doc" doc-reply
    "cider/complete-flush-caches" flush-caches-reply
    "complete-flush-caches" flush-caches-reply))
