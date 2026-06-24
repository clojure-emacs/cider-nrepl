(ns cider.nrepl.middleware.xref
  "Find function dependencies and function references."
  {:author "Bozhidar Batsov"
   :added "0.22"}
  (:require
   [cider.nrepl.middleware.util.error-handling :refer [with-safe-transport]]
   [clojure.java.io :as io]
   [orchard.meta :as meta]
   [orchard.misc :as misc]
   [orchard.xref :as xref]))

(defn- filename-as-url [filename]
  (if-let [resource (io/resource filename)]
    (str resource) ;; adds "file:" / "jar:file:" in front of the filename, besides from an absolute path
    filename))

(defn- xref-data [v]
  (let [var-meta (meta/var-meta v)]
    {:name (meta/var-name v)
     :doc (meta/var-doc 1 v)
     :file (:file var-meta)
     :file-url (or (:file-url var-meta)
                   (some-> var-meta :file filename-as-url))
     :line (:line var-meta)
     :column (:column var-meta)}))

(defn file-line-column [{:keys [file-url file line column]}]
  [(or file-url file) (or line 0) (or column 0)])

(defn fn-refs-reply [{:keys [ns sym]}]
  (let [var (ns-resolve (misc/as-sym ns) (misc/as-sym sym))]
    {:fn-refs (->> var
                   xref/fn-refs
                   (map xref-data)
                   (sort-by file-line-column))}))

(defn fn-deps-reply [{:keys [ns sym]}]
  (let [var (ns-resolve (misc/as-sym ns) (misc/as-sym sym))]
    {:fn-deps (->> var
                   xref/fn-deps
                   (map xref-data)
                   (sort-by file-line-column))}))

(defn- impl-data [{:keys [name file line column]}]
  {:name name
   :file file
   :file-url (some-> file filename-as-url)
   :line line
   :column column})

(defn who-implements-reply [{:keys [ns sym]}]
  (let [v (ns-resolve (misc/as-sym ns) (misc/as-sym sym))
        x (when v (deref v))]
    {:who-implements
     (cond
       (instance? clojure.lang.MultiFn x)
       {:kind "multimethod"
        :dispatch-values (xref/multimethod-dispatch-values v)}

       (and (map? x) (:on-interface x))
       {:kind "protocol"
        :impls (mapv impl-data (xref/protocol-impls v))}

       :else
       {:kind "other"})}))

(defn handle-xref [handler msg]
  (with-safe-transport handler msg
    "cider/fn-refs" fn-refs-reply
    "fn-refs" fn-refs-reply
    "cider/fn-deps" fn-deps-reply
    "fn-deps" fn-deps-reply
    "cider/who-implements" who-implements-reply))
