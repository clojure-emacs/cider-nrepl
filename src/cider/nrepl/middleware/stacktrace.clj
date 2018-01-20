(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.pprint :as pprint]
            [cider.nrepl.middleware.info :as info]
            [cider.nrepl.middleware.util.cljs :as cljs]
            [cider.nrepl.middleware.util.namespace :as namespace]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t]
            [orchard.misc :as u]
            [orchard.java :as java]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.tools.namespace.find :as nsfind])
  (:import (clojure.lang Compiler$CompilerException)))

;;; ## Stacktraces

;; Java stacktraces don't expose column number.
(defn stack-frame
  "Return a map describing the stack frame."
  [^StackTraceElement frame]
  {:name   (str (.getClassName frame) "/" (.getMethodName frame))
   :file   (.getFileName frame)
   :line   (.getLineNumber frame)
   :class  (.getClassName frame)
   :method (.getMethodName frame)})

(defn flag-frame
  "Update frame's flags vector to include the new flag."
  [frame flag]
  (update-in frame [:flags] (comp set conj) flag))

(defn- source-path
  "Return the relative source path for the class without extension."
  [class]
  (-> (str/replace (str class) #"\$.*" "")
      (str/replace "." "/")))

(defn- path->url
  "Return a url for the path, either relative to classpath, or absolute."
  [path]
  (or (info/file-path path) (second (info/resource-path path))))

(defn- frame->url
  "Return a java.net.URL to the file referenced in the frame, if possible.
  Useful for handling clojure vars which may not exist. Uncomprehensive list of
  reasons for this:
  * Failed refresh
  * Top-level evaluation"
  [frame]
  (some-> (:name frame)
          source-path
          (str "." (last (.split ^String (:file frame)
                                 "\\.")))
          path->url
          u/transform-value))

(defn analyze-fn
  "Add namespace, fn, and var to the frame map when the source is a Clojure
  function."
  [{:keys [file type class method] :as frame}]
  (if (or (= :clj type)
          (= :cljc type))
    (let [[ns fn & anons] (-> (repl/demunge class)
                              (str/replace #"--\d+" "")
                              (str/split #"/"))
          fn (or fn method)] ; protocol functions are not munged
      (assoc frame
             :ns  ns
             :fn  (str/join "/" (cons fn anons))
             :var (str ns "/" fn)
             ;; Full file path
             :file-url (or (some-> (info/info-clj 'user (symbol (str ns "/" fn)))
                                   :file
                                   path->url
                                   u/transform-value)
                           (u/transform-value (frame->url frame)))))
    (assoc frame :file-url (some->
                             (java/resolve-symbol 'user
                                                  (symbol (:name frame)))
                             :file
                             path->url
                             u/transform-value))))

(defn analyze-file
  "Associate the file type (extension) of the source file to the frame map, and
  add it as a flag. If the name is `NO_SOURCE_FILE`, type `clj` is assumed."
  [{:keys [file] :as frame}]
  (let [type (keyword
              (cond (nil? file)                "unknown"
                    (= file "NO_SOURCE_FILE")  "clj"
                    (neg? (.indexOf ^String file ".")) "unknown"
                    :else (last (.split ^String file "\\."))))]
    (-> frame
        (assoc :type type)
        (flag-frame type))))

(defn flag-repl
  "Flag the frame if its source is a REPL eval."
  [{:keys [file] :as frame}]
  (if (and file
           (or (= file "NO_SOURCE_FILE")
               (.startsWith ^String file "form-init")))
    (flag-frame frame :repl)
    frame))

(defn flag-tooling
  "Walk the call stack from top to bottom, flagging frames below the first call
  to `clojure.lang.Compiler` or `clojure.tools.nrepl.*` as `:tooling` to
  distinguish compilation and nREPL middleware frames from user code."
  [frames]
  (let [tool-regex #"^clojure\.lang\.Compiler|^clojure\.tools\.nrepl|^cider\."
        tool? #(re-find tool-regex (or (:name %) ""))
        flag  #(if (tool? %)
                 (flag-frame % :tooling)
                 %)]
    (map flag frames)))

(def directory-namespaces
  "This looks for all namespaces inside of directories on the class
  path, ignoring jars."
  (into #{} (namespace/project-namespaces)))

(def ns-common-prefix
  "In order to match more namespaces, we look for a common namespace
  prefix across the ones we have identified."
  (let [common
        (try (reduce
              (fn [common ns]
                (let [ns (str/lower-case ns)
                      matched (map vector common ns)
                      coincident (take-while (fn [[a b]] (= a b)) matched)]
                  (apply str (map first coincident))))
              (str/lower-case (first directory-namespaces))
              directory-namespaces)
             (catch Throwable e :error))]
    (condp = common
      ""
      {:valid false :common :missing}

      :error
      {:valid false :common :error}

      ;;default
      {:valid true :common common})))

(defn flag-project
  "Flag the frame if it is from the users project. From a users
  project means that the namespace is one we have identified or it
  begins with the identified common prefix."
  [{:keys [ns] :as frame}]
  (if (and directory-namespaces ns
           (or (contains? directory-namespaces (symbol ns))
               (when (:valid ns-common-prefix)
                 (.startsWith ^String ns (:common ns-common-prefix)))))
    (flag-frame frame :project)
    frame))

(defn flag-duplicates
  "Where a parent and child frame represent substantially the same source
  location, flag the parent as a duplicate."
  [frames]
  (cons (first frames)
        (map (fn [frame child]
               (if (or (= (:name frame) (:name child))
                       (and (= (:file frame) (:file child))
                            (= (:line frame) (:line child))))
                 (flag-frame frame :dup)
                 frame))
             (rest frames)
             frames)))

(defn analyze-frame
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [frame]
  ((comp flag-repl flag-project analyze-fn analyze-file stack-frame) frame))

(defn analyze-stacktrace
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [^Exception e]
  (-> (map analyze-frame (.getStackTrace e))
      (flag-duplicates)
      (flag-tooling)))

;;; ## Causes

(defn relative-path
  "If the path is under the project root, return the relative path; otherwise
  return the original path."
  [path]
  (let [dir (str (System/getProperty "user.dir")
                 (System/getProperty "file.separator"))]
    (str/replace-first path dir "")))

(defn extract-location
  "If the cause is a compiler exception, extract the useful location information
  from its message. Include relative path for simpler reporting."
  [{:keys [class message] :as cause}]
  (if (= class "clojure.lang.Compiler$CompilerException")
    (let [[_ msg file line column]
          (re-find #"(.*?), compiling:\((.*):(\d+):(\d+)\)" message)]
      (assoc cause
             :message msg
             :file file
             :file-url (some-> file
                               path->url
                               u/transform-value)
             :path (relative-path file)
             :line (Integer/parseInt line)
             :column (Integer/parseInt column)))
    cause))

;; CLJS REPLs use :repl-env to store huge amounts of analyzer/compiler state
(def ^:private ex-data-blacklist #{:repl-env})

(defn filtered-ex-data
  "Same as `ex-data`, but filters out entries whose keys are
  blacklisted (generally for containing data not intended for reading by a
  human)."
  [e]
  (when-let [data (ex-data e)]
    (into {} (filter (comp (complement ex-data-blacklist) key) data))))

(def spec-abbrev
  (delay
    (if (try (require 'clojure.spec) true
             (catch Throwable _ false))
      (resolve 'clojure.spec/abbrev)
      (if (try (require 'clojure.spec.alpha) true
               (catch Throwable _ false))
        (resolve 'clojure.spec.alpha/abbrev)
        #'identity))))

(defn prepare-spec-data
  "Prepare spec problems for display in user stacktraces.
  Take in a map `ed` as returned by `clojure.spec/explain-data` and return a map
  of pretty printed problems. The content of the returned map is modeled after
  `clojure.spec/explain-printer`."
  [ed pprint-fn]
  (let [pp-str #(with-out-str (pprint-fn %))
        problems (sort-by #(count (:path %))
                          (or (:clojure.spec/problems ed)
                              (:clojure.spec.alpha/problems ed)))]
    {:spec (pr-str (or (:clojure.spec/spec ed)
                       (:clojure.spec.alpha/spec ed)))
     :value (pp-str (or (:clojure.spec/value ed)
                        (:clojure.spec.alpha/value ed)))
     :problems (for [{:keys [in val
                             pred reason
                             via path]
                      :as prob} problems]
                 (->> {:in (when-not (empty? in) (pr-str in))
                       :val (pp-str val)
                       :predicate (pr-str (@spec-abbrev pred))
                       :reason reason ; <- always nil or string
                       :spec (when-not (empty? via) (pr-str (last via)))
                       :at (when-not (empty? path) (pr-str path))
                       :extra (let [extras (->> #{:in :val :pred :reason :via :path
                                                  :clojure.spec/failure
                                                  :clojure.spec.alpha/failure}
                                                (set/difference (set (keys prob)))
                                                (select-keys prob))]
                                (when-not (empty? extras)
                                  (pp-str extras)))}
                      (filter clojure.core/val)
                      (into {})))}))

(defn analyze-cause
  "Return a map describing the exception cause. If `ex-data` exists, a `:data`
  key is appended."
  [^Exception e pprint-fn]
  (let [m {:class (.getName (class e))
           :message (.getMessage e)
           :stacktrace (analyze-stacktrace e)}]
    (if-let [data (filtered-ex-data e)]
      (if (or (:clojure.spec/failure data)
              (:clojure.spec.alpha/failure data))
        (assoc m
               :message "Spec assertion failed."
               :spec (prepare-spec-data data pprint-fn))
        (assoc m
               :data (with-out-str (pprint-fn data))))
      m)))

(defn analyze-causes
  "Return the cause chain beginning with the thrown exception, with stack frames
  for each. For `ex-info` exceptions response contains :data slot with pretty
  printed data. For clojure.spec asserts, :spec slot contains a map of pretty
  printed components describing spec failures."
  [e pprint-fn]
  (->> e
       (iterate #(.getCause ^Exception %))
       (take-while identity)
       (map (comp extract-location #(analyze-cause % pprint-fn)))))

;;; ## Middleware

(defn handle-stacktrace
  [_ {:keys [session transport pprint-fn] :as msg}]
  (if-let [e (@session #'*e)]
    (doseq [cause (analyze-causes e pprint-fn)]
      (t/send transport (response-for msg cause)))
    (t/send transport (response-for msg :status :no-error)))
  (t/send transport (response-for msg :status :done)))
