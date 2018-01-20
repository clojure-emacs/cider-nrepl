(ns cider.nrepl.middleware.util.meta
  "Utility functions for extracting and manipulating metadata."
  (:require [orchard.misc :as u]
            [cider.nrepl.middleware.util.namespace :as ns]
            [cider.nrepl.middleware.util.spec :as spec]
            [cider.nrepl.middleware.spec :as spec-mid]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [clojure.lang LineNumberingPushbackReader]))

;;; ## Extractors

(defn- format-spec-descripton
  "Format the spec description to display each predicate on a new line."
  [description]
  (if (seq? description)
    (->> description
         (map #(with-out-str (pprint/pprint %)))
         str/join
         str/trim-newline
         (format "(%s)"))
    (->>  description
          pprint/pprint
          with-out-str
          str/trim-newline)))

(defn- format-spec
  "Return sequence of [role spec-description] pairs."
  [fnspec]
  (for [role [:args :ret :fn]
        :let [spec (get fnspec role)]
        :when spec]
    [role (-> spec spec/describe format-spec-descripton)]))

(declare var-name)

(defn- maybe-add-spec
  "If the var `v` has a spec has associated with it, assoc that into meta-map.
  The spec is formatted to avoid processing it in CIDER."
  [v meta-map]
  (if-let [spec (when v (spec-mid/spec-form (var-name v)))]
    (merge meta-map {:spec spec})
    meta-map))

(defn- maybe-add-url
  "If `info` has a non blank :url or it's a :special-form build a :url
  entry pointing to https://clojure.org/..."
  [info]
  (if-let [url (cond
                 (not (str/blank? (:url info)))
                 (str "https://clojure.org/" (:url info))

                 (:special-form info)
                 (str "https://clojure.org/special_forms#" (:name info)))]
    (assoc info :url url)
    info))

(defn- maybe-add-file
  "If `meta-map` has no :file, assoc the :namespace file into it."
  [{:keys [file ns] :as meta-map}]
  ;; If we don't know its file, use the ns file.
  (if (and ns (or (not file)
                  (re-find #"/form-init[^/]*$" file)))
    (-> (dissoc meta-map :line)
        (assoc :file (ns/ns-path ns)))
    meta-map))

(defn- maybe-protocol
  [info]
  (if-let [prot-meta (meta (:protocol info))]
    (merge info {:file (:file prot-meta)
                 :line (:line prot-meta)})
    info))

(defn- map-seq [x]
  (if (seq x)
    x
    nil))

(defn resolve-var
  [ns sym]
  (if-let [ns (find-ns ns)]
    (try (ns-resolve ns sym)
         ;; Impl might try to resolve it as a class, which may fail
         (catch ClassNotFoundException _
           nil)
         ;; TODO: Preserve and display the exception info
         (catch Exception _
           nil))))

(defn resolve-aliases
  [ns]
  (if-let [ns (find-ns ns)]
    (ns-aliases ns)))

;; Even if things like catch or finally aren't clojure special
;; symbols we want to be able to talk about them.
;; They just map to a special symbol.
(def special-sub-symbs '{& fn*, catch try, finally try})

;; What I find very confusing in Clojure documentation is the use of "special form" which is not a concept,
;; just an annotation on vars (always macros) that are special forms in other lisps.
;;
;; In Clojure, real "special forms" are called special symbols:
;;
;; user> (keys (. clojure.lang.Compiler specials))
;; (& let* monitor-exit case* fn* try reify* ... )
;;
;; and even if the oficial documentation says let is a special form,
;; it isn't special at all, let* is, while let is just a macro.
;;
;; Looking at Clojure code I couldn't find any use of :special-form annotation
;; apart from printing a label at the repl doc.
;; Here we only take care of special symbols, look var-meta for stuff like let, fn, defn, etc.
(defn special-sym-meta
  "Return info for the symbol if it's a special-symbol?, or nil otherwise."
  [sym]
  (let [orig-sym sym
        sym (get special-sub-symbs sym sym)
        compiler-special? (special-symbol? orig-sym)]
    (when-let [m (and compiler-special? (#'repl/special-doc sym))]
      (-> m
          (assoc :name orig-sym)
          maybe-add-url))))

(def var-meta-whitelist
  [:ns :name :doc :file :arglists :forms :macro :special-form
   :protocol :line :column :static :added :deprecated :resource])

(defn var-meta
  "Return a map of metadata for var or special form v.
  If whitelist is missing use var-meta-whitelist."
  ([v] (var-meta v var-meta-whitelist))
  ([v whitelist]
   (when (var? v)
    (let [meta-map (-> (meta v)
                       maybe-protocol
                       (select-keys (or whitelist var-meta-whitelist))
                       map-seq maybe-add-file maybe-add-url)]
      (maybe-add-spec v meta-map)))))



(def special-forms
  "Special forms that can be apropo'ed."
  (concat (keys (var-get #'clojure.repl/special-doc-map))
          '[& catch finally]))

(defn var-name
  "Return special form or var's namespace-qualified name as string."
  [v]
  (let [mta (or (special-sym-meta v)
                (meta v))]
    (if-let [ns (:ns mta)]
      (str (ns-name ns) "/" (:name mta))
      (name (:name mta)))))

(defn var-doc
  "Return special form or var's docstring, optionally limiting the number of
  sentences returned to n."
  ([v]
   (or (:doc (or (special-sym-meta v)
                 (meta v)))
       "(not documented)"))
  ([n v]
   (->> (-> (var-doc v)
            (str/replace #"\s+" " ") ; normalize whitespace
            (str/split #"(?<=\.) ")) ; split sentences
        (take n)
        (str/join " "))))

(defn var-code
  "Find the source of the var `v`.
  Return a map of the var's metadata (:file, :line, :column, etc.) as well as:
    - :form : The form, as read by `clojure.core/read`, and
    - :code : The source code of the form
  Return nil if the source of the var cannot be found."
  [v]
  (when-let [{:keys [file line column] :as var-meta} (var-meta v)]
    ;; file can be either absolute (eg: functions that have been eval-ed with
    ;; C-M-x), or relative to some path on the classpath.
    (when-let [res (or (io/resource file)
                       (let [f (io/file file)]
                         (when (.exists f)
                           f)))]
      (with-open [rdr (LineNumberingPushbackReader. (io/reader res))]
        ;; Skip to the right line
        (dotimes [_ (dec line)]
          (.readLine rdr))

        ;; Reader that collects the code as a string. Adapted from
        ;; clojure.repl/source.
        (let [text     (StringBuilder.)
              collect? (atom false)
              pbr      (proxy [LineNumberingPushbackReader] [rdr]
                         (read []
                           (let [i (proxy-super read)]
                             (when @collect?
                               (.append text (char i)))
                             i))
                         (unread [c]
                           (when @collect?
                             (.deleteCharAt text (dec (.length text))))
                           (proxy-super unread c)))
              ;; Fix bogus column number of 1, which really means 0
              column   (if (= 1 column) 0 column)]

          ;; Give it the right line and column number. We can just set the
          ;; line number directly, but there is no setColumnNumber method, so
          ;; we have to hack it a bit.
          (.setLineNumber pbr (.getLineNumber rdr))
          (dotimes [_ column]
            (.read pbr))

          ;; Now start collecting the code
          (reset! collect? true)

          (let [form (read {} pbr)
                code (str text)]
            (assoc var-meta
                   :form form
                   :code code)))))))

(defn ns-meta
  [ns]
  (when ns
    (merge
     (meta ns)
     {:ns ns
      :file (-> (ns-publics ns)
                first
                second
                var-meta
                :file)
      :line 1})))

;;; ## Manipulation

(defn merge-meta
  "Non-throwing version of (vary-meta obj merge metamap-1 metamap-2 ...).
  Like `vary-meta`, this only applies to immutable objects. For
  instance, this function does nothing on atoms, because the metadata
  of an `atom` is part of the atom itself and can only be changed
  destructively."
  {:style/indent 1}
  [obj & metamaps]
  (try
    (apply vary-meta obj merge metamaps)
    (catch Exception e obj)))

(defn strip-meta
  "Strip meta from form.
  If keys are provided, strip only those keys."
  ([form] (strip-meta form nil))
  ([form keys]
   (if (and (instance? clojure.lang.IObj form)
            (meta form))
     (if keys
       (with-meta form (apply dissoc (meta form) keys))
       (with-meta form nil))
     form)))

(defn macroexpand-all
  "Like clojure.walk/macroexpand-all, but preserves and macroexpands
  metadata. Also store the original form (unexpanded and stripped of
  metadata) in the metadata of the expanded form under original-key."
  [form & [original-key]]
  (let [md (meta form)
        expanded (walk/walk #(macroexpand-all % original-key)
                            identity
                            (if (seq? form)
                              ;; Without this, `macroexpand-all`
                              ;; throws if called on `defrecords`.
                              (try (macroexpand form)
                                   (catch ClassNotFoundException e form))
                              form))]
    (if md
      ;; Macroexpand the metadata too, because sometimes metadata
      ;; contains, for example, functions. This is the case for
      ;; deftest forms.
      (merge-meta expanded
        (macroexpand-all md)
        (when original-key
          ;; We have to quote this, or it will get evaluated by
          ;; Clojure (even though it's inside meta).
          {original-key (list 'quote (strip-meta form))}))
      expanded)))

(def relevant-meta-keys
  "Metadata keys that are useful to us.
  This is used so that we don't crowd the ns cache with useless or
  redudant information, such as :name and :ns."
  [:indent :deprecated :macro :arglists :test :doc :fn
   :cider/instrumented :style/indent :clojure.tools.trace/traced])

(defn relevant-meta
  "Filter the entries in map m by `relevant-meta-keys` and non-nil values."
  [m]
  (->> (select-keys m relevant-meta-keys)
       (filter second)
       (u/update-vals pr-str)))
