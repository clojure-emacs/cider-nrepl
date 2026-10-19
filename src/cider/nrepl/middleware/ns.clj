(ns cider.nrepl.middleware.ns
  (:refer-clojure :exclude [ns-aliases])
  (:require
   [cider.nrepl.middleware.util :as util]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.util.coerce :as util.coerce]
   [cider.nrepl.middleware.util.error-handling :refer [with-op-aliases with-safe-transport]]
   [cider.nrepl.middleware.util.meta :as um]
   [orchard.cljs.analysis :as cljs-analysis]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [compliment.utils :as compliment.utils]
   [orchard.info :as info]
   [orchard.misc :as misc]
   [orchard.namespace :as ns]
   [orchard.query :as query])
  (:import
   (clojure.lang Compiler IRecord IType Namespace RT Var)))

(defn ns-list-vars-by-name
  "Return a list of vars named `name` amongst all namespaces.
  `name` is a symbol."
  [name]
  (->> (mapcat ns-interns (all-ns))
       (filter #(= (first %) name))
       (map second)))

(defn ns-vars-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map (comp str :name meta))
       sort))

(defn ns-vars-with-meta-clj [ns & [var-query]]
  (->> {:ns-query {:exactly [ns]}}
       (merge var-query)
       util.coerce/var-query
       query/vars
       (map meta)
       (map (juxt (comp str :name) um/relevant-meta))
       (into (sorted-map))))

(defn ns-list-cljs [env]
  (->> (cljs-analysis/all-ns env)
       keys
       (map name)
       sort))

(defn ns-vars-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         keys
         (map name)
         sort)))

(defn ns-vars-with-meta-cljs [env ns & [var-query]]
  (let [fetch-vars (if (:private? var-query)
                     (partial cljs-analysis/ns-interns-from-env env)
                     (partial cljs-analysis/public-vars env))]
    (->> (symbol ns)
         fetch-vars
         (misc/update-vals (comp um/relevant-meta :meta))
         (misc/update-keys name)
         (into (sorted-map)))))

(defn ns-list [{:keys [filter-regexps] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-list-cljs cljs-env)
    (ns/loaded-namespaces filter-regexps)))

(defn ns-vars [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-cljs cljs-env ns var-query)
    (ns-vars-clj ns var-query)))

(defn ns-vars-with-meta [{:keys [ns var-query] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-vars-with-meta-cljs cljs-env ns var-query)
    (ns-vars-with-meta-clj ns var-query)))

(defn ns-path [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (:file (info/info* {:dialect :cljs
                        :env cljs-env
                        :sym (symbol ns)}))
    (str (ns/canonical-source ns))))

(defn ns-list-reply [msg]
  {:ns-list (ns-list msg)})

(defn ns-list-vars-by-name-reply [{:keys [name]}]
  {:var-list (pr-str (ns-list-vars-by-name (symbol name)))})

(defn ns-vars-reply
  [msg]
  {:ns-vars (ns-vars msg)})

(defn ns-vars-with-meta-reply
  [msg]
  {:ns-vars-with-meta (ns-vars-with-meta msg)})

(defn- ns-path-reply [msg]
  (let [v (ns-path msg)
        cljs? (cljs/grab-cljs-env msg)]
    {:path v
     :url (if cljs?
            (or (some-> v io/resource str)
                v)
            v)}))

(defn- ns-load-all-reply
  [_msg]
  {:loaded-ns (ns/load-project-namespaces)})

(defn- ns-aliases-clj [ns]
  (->> (symbol ns)
       (clojure.core/ns-aliases)
       (misc/update-vals ns-name)
       (util/transform-value)))

(defn- ns-aliases-cljs [env ns]
  (->> (cljs-analysis/ns-aliases env ns)
       (remove (fn [[k v]] (= k v)))
       (into {})
       (util/transform-value)))

(defn ns-aliases [{:keys [ns] :as msg}]
  (if-let [cljs-env (cljs/grab-cljs-env msg)]
    (ns-aliases-cljs cljs-env ns)
    (ns-aliases-clj ns)))

(defn- ns-aliases-reply [msg]
  {:ns-aliases (ns-aliases msg)})

;;; Resolving missing symbols
;;
;; `cider/resolve-missing` lists what could provide a symbol the current
;; namespace can't resolve yet: namespaces with a public var by that name, to
;; require, and classes by that name, to import.  A symbol that already resolves
;; gets nothing, since the other namespaces with a var by that name would only
;; change what it means.

(defn- split-symbol
  "Split the symbol string `s` into its qualifier (nil when unqualified) and its
  name.  A trailing dot, as in a constructor call, is dropped."
  [^String s]
  (let [s (if (and (.endsWith s ".") (> (count s) 1))
            (subs s 0 (dec (count s)))
            s)
        i (.indexOf s "/")]
    (if (and (pos? i) (< i (dec (count s))))
      [(subs s 0 i) (subs s (inc i))]
      [nil s])))

(defn- clojure-type?
  "Whether `c` was made by `deftype` or `defrecord`."
  [^Class c]
  (or (.isAssignableFrom IType c)
      (.isAssignableFrom IRecord c)))

(defn- defining-ns
  "The name of the namespace that defines the type `c`."
  [^Class c]
  (let [class-name (.getName c)
        package (subs class-name 0 (max 0 (.lastIndexOf class-name ".")))]
    (or (some #(let [ns-str (str (ns-name %))]
                 (when (= package (munge ns-str)) ns-str))
              (all-ns))
        (Compiler/demunge package))))

(defn- jdk-internal?
  "Whether `class-name` belongs to the JDK's own implementation."
  [^String class-name]
  (or (.startsWith class-name "jdk.internal.")
      (.startsWith class-name "sun.")
      (and (.startsWith class-name "com.sun.")
           (.contains class-name ".internal."))))

(defn- class-candidates
  "The classes named `short-name`.  Besides the classes on the classpath, this
  covers the ones loaded namespaces import, which includes those made by
  `deftype` and `defrecord`."
  [^String short-name]
  (when-not (str/includes? short-name ".")
    (let [sym (symbol short-name)
          loaded (into #{}
                       (comp (remove ns/inlined-dependency?)
                             (keep #(let [m (.getMapping ^Namespace % sym)]
                                      (when (class? m) m))))
                       (all-ns))
          loaded-names (into #{} (map #(.getName ^Class %)) loaded)
          suffix (str "." short-name)]
      (concat
       (for [^Class c loaded]
         (if (clojure-type? c)
           {:name (.getName c) :type "type" :ns (defining-ns c)}
           {:name (.getName c) :type "class"}))
       (for [^String c (compliment.utils/classes-on-classpath)
             :when (and (.endsWith c suffix)
                        (not (loaded-names c))
                        (not (jdk-internal? c)))]
         {:name c :type "class"})))))

(defn- var-candidates
  "The loaded namespaces with a public var named `var-name`."
  [var-name]
  (let [sym (symbol var-name)]
    (for [ns (all-ns)
          :let [v (.findInternedVar ^Namespace ns sym)]
          :when (and v (.isPublic ^Var v) (not (ns/inlined-dependency? ns)))]
      {:name (str (ns-name ns)) :type "ns"})))

(defn- class-named
  "The class named `class-name`, or nil.  Unlike `ns-resolve`, this doesn't
  initialize the class, which would run its static initializer."
  [^String class-name]
  (try (RT/classForNameNonLoading class-name)
       ;; e.g. NoClassDefFoundError for a name in the wrong case
       (catch Exception _)
       (catch LinkageError _)))

(defn- named-ns
  "The namespace a qualifier names in the namespace `the-ns` without
  depending on what else got loaded: an alias, `the-ns` itself or
  `clojure.core`."
  [^Namespace the-ns q]
  (or (.lookupAlias the-ns q)
      (when (#{(ns-name the-ns) 'clojure.core} q)
        (find-ns q))))

(defn- clj-resolves?
  "Whether `qualifier`/`var-name` already means something in the namespace
  `the-ns`."
  [^Namespace the-ns qualifier var-name]
  (let [sym (symbol var-name)]
    (if qualifier
      (let [q (symbol qualifier)]
        (if-let [^Namespace target (named-ns the-ns q)]
          (some? (.findInternedVar target sym))
          ;; a member of a class the namespace can use
          (class? (if (str/includes? qualifier ".")
                    (class-named qualifier)
                    (.getMapping the-ns q)))))
      (or (special-symbol? sym)
          (some? (.getMapping the-ns sym))))))

(defn- resolve-missing-clj [ns-sym qualifier var-name]
  (let [the-ns (some-> ns-sym find-ns)]
    (when-not (and the-ns (clj-resolves? the-ns qualifier var-name))
      (let [qualifier-classes (when qualifier (class-candidates qualifier))]
        (concat qualifier-classes
                ;; After a slash comes a member, not a class.
                (when-not qualifier (class-candidates var-name))
                ;; A class before the slash makes it a static member.
                (when-not (seq qualifier-classes)
                  (var-candidates var-name)))))))

(defn- cljs-defines?
  "Whether the ClojureScript namespace `ns-sym` defines `sym`, as a var or as a
  macro in the Clojure namespace of the same name."
  [env ns-sym sym]
  (boolean
   (when ns-sym
     (or (get-in (cljs-analysis/find-ns env ns-sym) [:defs sym])
         (some-> ^Namespace (find-ns ns-sym) (.findInternedVar sym) .isMacro)))))

(defn- cljs-resolves?
  "Whether `qualifier`/`sym` already means something in the ClojureScript
  namespace `current`."
  [env current qualifier sym]
  (if qualifier
    (let [q (symbol qualifier)]
      (or (cljs-defines? env (cljs-analysis/ns-alias env q current) sym)
          (cljs-defines? env (cljs-analysis/macro-ns-alias env q current) sym)
          (cljs-defines? env (if (= q 'clojure.core) 'cljs.core (#{current 'cljs.core} q)) sym)))
    (let [ns-info (cljs-analysis/find-ns env current)]
      (or (special-symbol? sym)
          (contains? (cljs-analysis/referred-vars env current) sym)
          (contains? (cljs-analysis/referred-macros env current) sym)
          (contains? (:renames ns-info) sym)
          (contains? (:rename-macros ns-info) sym)
          (cljs-defines? env current sym)
          (contains? (cljs-analysis/core-vars env current) sym)
          (contains? (cljs-analysis/core-macros env current) sym)))))

(defn- resolve-missing-cljs [env current qualifier var-name]
  (let [sym (symbol var-name)]
    (when-not (and current
                   (cljs-analysis/find-ns env current)
                   (cljs-resolves? env current qualifier sym))
      (for [[ns-sym ns-info] (cljs-analysis/all-ns env)
            ;; A Node REPL's compiler env has a namespace without a name.
            :when (and ns-sym (not (#{'cljs.core current} ns-sym)))
            :let [v (get-in ns-info [:defs sym])
                  ;; Macros live in the Clojure namespace of the same name.
                  ^Var m (some-> ^Namespace (find-ns ns-sym) (.findInternedVar sym))
                  candidate-type (cond
                                   (and v (not (:private v)) (not (:anonymous v))) "ns"
                                   (and m (.isMacro m) (.isPublic m)) "macro")]
            :when candidate-type]
        {:name (str ns-sym) :type candidate-type}))))

(defn resolve-missing
  "The namespaces and classes that could provide the `:symbol` of `msg` in its
  `:ns`, as maps with a `:name` and a `:type` (`ns`, `macro`, `class` or
  `type`).  A `type` candidate also has the `:ns` that defines it."
  [{sym :symbol ns-str :ns :as msg}]
  (when (str/blank? sym)
    (throw (IllegalArgumentException. "No symbol to resolve")))
  (let [[qualifier var-name] (split-symbol (str/trim sym))
        ns-sym (some-> ns-str not-empty symbol)
        cljs-env (cljs/grab-cljs-env msg)
        candidates (if cljs-env
                     (resolve-missing-cljs cljs-env ns-sym qualifier var-name)
                     (resolve-missing-clj ns-sym qualifier var-name))
        named (filter #(= qualifier (:name %)) candidates)
        ;; Making a namespace's name the alias of another namespace would
        ;; change what it means.
        ns-name? (when qualifier
                   (let [q (symbol qualifier)]
                     (if cljs-env
                       (or (#{'cljs.core 'clojure.core} q)
                           (cljs-analysis/find-ns cljs-env q))
                       (find-ns q))))]
    ;; A fully qualified symbol leaves no choice.
    (->> (if (or ns-name? (seq named)) named candidates)
         distinct
         (sort-by :name))))

(defn- resolve-missing-reply [msg]
  {:candidates (resolve-missing msg)})

(defn handle-ns [handler msg]
  (with-safe-transport handler msg
    (merge
     (with-op-aliases {"cider/ns-list" ns-list-reply
                       "cider/ns-list-vars-by-name" ns-list-vars-by-name-reply
                       "cider/ns-vars" ns-vars-reply
                       "cider/ns-vars-with-meta" ns-vars-with-meta-reply
                       "cider/ns-path" ns-path-reply
                       "cider/ns-load-all" ns-load-all-reply
                       "cider/ns-aliases" ns-aliases-reply})
     ;; Newer, with no unqualified alias, which would shadow refactor-nrepl's
     ;; `resolve-missing`.
     {"cider/resolve-missing" resolve-missing-reply})))
