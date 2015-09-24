(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require [cider.nrepl.middleware.util.cljs :as cljs]
            [clojure.pprint :as pp]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.misc :refer [response-for]]
            [clojure.tools.nrepl.transport :as t])
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

(defn analyze-fn
  "Add namespace, fn, and var to the frame map when the source is a Clojure
  function."
  [{:keys [file type class method] :as frame}]
  (if (= :clj type)
    (let [[ns fn & anons] (-> (repl/demunge class)
                              (str/replace #"--\d+" "")
                              (str/split #"/"))
          fn (or fn method)] ; protocol functions are not munged
      (assoc frame
             :ns  ns
             :fn  (str/join "/" (cons fn anons))
             :var (str ns "/" fn)))
    frame))

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
        (update-in [:flags] (comp set conj) type))))

(defn flag-repl
  "Flag the frame if its source is a REPL eval."
  [{:keys [file] :as frame}]
  (if (and file
           (or (= file "NO_SOURCE_FILE")
               (.startsWith ^String file "form-init")))
    (update-in frame [:flags] (comp set conj) :repl)
    frame))

(defn flag-tooling
  "Walk the call stack from top to bottom, flagging frames below the first call
  to `clojure.lang.Compiler` or `clojure.tools.nrepl.*` as `:tooling` to
  distinguish compilation and nREPL middleware frames from user code."
  [frames]
  (let [tool? #(re-find #"clojure.lang.Compiler|clojure.tools.nrepl" (:name %))
        flag  #(update-in % [:flags] (comp set conj) :tooling)
        [user & tools] (partition-by (complement tool?) frames)]
    (concat user (map flag (apply concat tools)))))

(defn flag-duplicates
  "Where a parent and child frame represent substantially the same source
  location, flag the parent as a duplicate."
  [frames]
  (cons (first frames)
        (map (fn [frame child]
               (if (or (= (:name frame) (:name child))
                       (and (= (:file frame) (:file child))
                            (= (:line frame) (:line child))))
                 (update-in frame [:flags] (comp set conj) :dup)
                 frame))
             (rest frames)
             frames)))

(defn analyze-frame
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [frame]
  ((comp flag-repl analyze-fn analyze-file stack-frame) frame))

(defn analyze-stacktrace
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [^Exception e]
  (-> (map analyze-frame (.getStackTrace e))
      (flag-duplicates)
      (flag-tooling)))

;;; ## Causes

(defn extract-location
  "If the cause is a compiler exception, extract the useful location information
  from its message and discard the string representation of its inner cause."
  [{:keys [class] :as cause}]
  (if (= class "clojure.lang.Compiler$CompilerException")
    (let [re #".* (compiling:)\(([^:]*):(\d+):(\d+)\)"
          [_ label source line column] (re-matches re (:message cause))]
      (assoc (update-in cause [:message] str/replace re
                        (str "Error " label " " source " line " line " col " column))
             :source source
             :line line
             :column column))
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

(defn analyze-cause
  "Return a map describing the exception cause. If `ex-data` exists, a `:data`
  key is appended."
  [^Exception e print-length print-level]
  (let [m {:class (.getName (class e))
           :message (.getMessage e)
           :stacktrace (analyze-stacktrace e)}]
    (if-let [data (filtered-ex-data e)]
      (assoc m :data (binding [*print-length* print-length
                               *print-level* print-level]
                       (with-out-str (pp/pprint data))))
      m)))

(defn analyze-causes
  "Return the cause chain beginning with the thrown exception, with stack frames
  for each."
  [e print-length print-level]
  (->> e
       (iterate #(.getCause ^Exception %))
       (take-while identity)
       (map (comp extract-location #(analyze-cause % print-length print-level)))))

;;; ## Middleware

(defn wrap-stacktrace-reply
  [{:keys [session transport print-length print-level] :as msg}]
  ;; no stacktrace support for cljs currently - they are printed by piggieback anyway
  (if-let [e (and (not (cljs/grab-cljs-env msg))
                  (@session #'*e))]
    (doseq [cause (analyze-causes e print-length print-level)]
      (t/send transport (response-for msg cause)))
    (t/send transport (response-for msg :status :no-error)))
  (t/send transport (response-for msg :status :done)))

(defn wrap-stacktrace
  "Middleware that handles stacktrace requests, sending cause and stack frame
  info for the most recent exception."
  [handler]
  (fn [{:keys [op] :as msg}]
    (if (= "stacktrace" op)
      (wrap-stacktrace-reply msg)
      (handler msg))))

;; nREPL middleware descriptor info
(set-descriptor!
 #'wrap-stacktrace
 (cljs/requires-piggieback
  {:requires #{#'session}
   :expects #{}
   :handles {"stacktrace"
             {:doc (str "Return messages describing each cause and stack frame "
                        "of the most recent exception.")
              :returns {"status" "\"done\", or \"no-error\" if `*e` is nil"}}}}))
