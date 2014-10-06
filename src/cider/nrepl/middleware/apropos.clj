(ns cider.nrepl.middleware.apropos
  "Search symbols and docs matching a regular expression"
  {:author "Jeff Valk"}
  (:require [clojure.string :as str]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t]
            [cider.nrepl.middleware.util.misc :as u]))

;;; ## Overview
;; This middleware provides regular expression search across namespaces for
;; both symbols and documentation. Results ordered for the most common usages:
;; symbols from the current namespace are preferred, then `clojure.*` symbols,
;; and then other results.


;;; ## Metadata
;; Var metadata provides the search targets. In the case of docstrings, an
;; abbreviated version (i.e. first sentence only) may be returned for
;; symbol-only searches.

(defn var-name
  "Return a var's namespace-qualified name as a string."
  [v]
  (str/join "/" ((juxt (comp ns-name :ns) :name)
                 (meta v))))

(defn var-doc
  "Return a var's docstring, optionally limiting the number of sentences
  returned."
  ([v]
     (or (:doc (meta v)) "(not documented)"))
  ([n v]
     (->> (-> (var-doc v)
              (str/replace #"\s+" " ") ; normalize whitespace
              (str/split #"(?<=\.) ")) ; split sentences
          (take n)
          (str/join " "))))


;;; ## Symbol Search

(defn namespaces
  "Return the list of namespaces to be searched, ordered with `ns` first,
  followed by `clojure.*` namespaces, and then all others sorted alphabetically.
  If `search-ns` is specified, the returned list will contain only this
  namespace."
  [ns search-ns]
  (let [clojure-ns? #(.startsWith (str (ns-name %)) "clojure.")
        current-ns (find-ns (symbol (or ns "")))]
    (if search-ns
      (list (find-ns (symbol search-ns)))
      (sort (fn [x y]
              (cond (= x current-ns) -1
                    (= y current-ns)  1
                    (and (clojure-ns? x) (not (clojure-ns? y))) -1
                    (and (clojure-ns? y) (not (clojure-ns? x)))  1
                    :else (compare (str x) (str y))))
            (all-ns)))))

(defn find-symbols
  "Find symbols or (optionally) docstrings matching `query` in `search-ns` if
  specified or all namespaces. The search may optionally include private vars,
  and may be case senstive. Types returned correspond to Apropos types.
  Docstring search returns the full doc; symbol search returns an abbreviated
  version."
  [ns query search-ns docs? privates? case-sensitive?]
  (let [ns-vars     (if privates? ns-interns ns-publics)
        var-doc*    (if docs? var-doc (partial var-doc 1))
        search-prop (if docs? var-doc var-name)
        regex   (-> (if case-sensitive? query (format "(?i:%s)" query)) re-pattern)]
    (->> (namespaces ns search-ns)
         (mapcat (comp (partial sort-by var-name) vals ns-vars))
         (filter (comp (partial re-find regex) search-prop))
         (map (fn [v] {:name (var-name v)
                       :doc  (var-doc* v)
                       :type (cond (:macro (meta v)) :macro
                                   (fn? (deref v))   :function
                                   :else             :variable)})))))


;;; ## Middleware

(defn handle-apropos
  "Return a sequence of vars whose name matches the query pattern, or if
  specified, having the pattern in their docstring."
  [{:keys [ns query search-ns docs? privates? case-sensitive? transport] :as msg}]
  (try
    (let [results (find-symbols ns query search-ns docs? privates? case-sensitive?)]
      (t/send transport (response-for msg :apropos-matches results :status :done)))
    (catch Exception e
      (t/send
       transport
       (response-for msg (u/err-info e :apropos-error))))))

(defn wrap-apropos
  "Middleware that handles apropos requests"
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= op "apropos")
      (handle-apropos msg)
      (handler msg))))

;; nREPL middleware descriptor info
(set-descriptor!
 #'wrap-apropos
 {:handles
  {"apropos"
   {:doc (:doc (meta #'handle-apropos))
    :requires {"query" "The search query."}
    :returns {"apropos-matches" "A list of matching symbols."}}}})
