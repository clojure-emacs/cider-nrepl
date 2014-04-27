(ns cider.nrepl.middleware.stacktrace
  "Cause and stacktrace analysis for exceptions"
  {:author "Jeff Valk"}
  (:require [clojure.pprint :as pp]
            [clojure.repl :as repl]
            [clojure.string :as str]
            [clojure.tools.nrepl.middleware :refer [set-descriptor!]]
            [clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
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
  [{:keys [file type class] :as frame}]
  (if (= :clj type)
    (let [[ns fn & anons] (-> (repl/demunge class) (str/split #"/"))
          anons (map #(str/replace % #"--\d{4,}" "") anons)]
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
                    (neg? (.indexOf file ".")) "unknown"
                    :else (last (.split file "\\."))))]
    (-> frame
        (assoc :type type)
        (update-in [:flags] (comp set conj) type))))

(defn flag-repl
  "Flag the frame if its source is a REPL eval."
  [{:keys [file] :as frame}]
  (if (and file
           (or (= file "NO_SOURCE_FILE")
               (.startsWith file "form-init")))
    (update-in frame [:flags] (comp set conj) :repl)
    frame))

(defn flag-tooling
  "Walk the call stack from bottom to top, flagging frames below the *lowest*
  call to `clojure.lang.Compiler/eval` as `:tooling` to distinguish compilation
  and nREPL middleware frames from user code."
  [frames]
  (let [eval? #(= (:name %) "clojure.lang.Compiler/eval")
        flag  #(update-in % [:flags] (comp set conj) :tooling)
        [tools evals & user] (partition-by eval? (reverse frames))]
    (reverse (apply concat
                    (map flag tools)
                    (map flag evals)
                    user))))

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

(defn analyze-stacktrace
  "Return the stacktrace as a sequence of maps, each describing a stack frame."
  [e]
  (-> (map (comp flag-repl analyze-fn analyze-file stack-frame)
           (.getStackTrace e))
      (flag-duplicates)
      (flag-tooling)))


;;; ## Causes

(defn analyze-causes
  "Return the cause chain, beginning with the thrown exception, or, if the
  thrown exception is a wrapping compiler exception, its immediate cause.
  If `ex-data` exists for any item, a `:data` key is appended."
  [e]
  (->> e
       (iterate #(.getCause %))
       (take-while identity)
       (map #(let [m {:class (.getName (class %))
                      :message (.getMessage %)}]
               (if-let [data (ex-data %)]
                 (assoc m :data (with-out-str (pp/pprint data)))
                 m)))))


;;; ## Middleware

(defn wrap-stacktrace
  "Middleware that handles stacktrace requests, sending cause and stack frame
  info for the most recent exception."
  [handler]
  (fn [{:keys [op session transport] :as msg}]
    (if (= "stacktrace" op)
      (do (if-let [e (@session #'*e)]
            (do (doseq [cause (analyze-causes e)]
                  (t/send transport (response-for msg cause)))
                (doseq [frame (analyze-stacktrace e)]
                  (t/send transport (response-for msg frame)))
                (t/send transport (response-for msg :status :done)))
            (t/send transport (response-for msg :status :no-error))))
      (handler msg))))

;; nREPL middleware descriptor info
(set-descriptor!
 #'wrap-stacktrace
 {:requires #{#'session}
  :expects #{#'pr-values}
  :handles {"stacktrace"
            {:doc (str "Return messages describing each cause and stack frame "
                       "of the most recent exception.")
             :returns {"status" "\"done\", or \"no-error\" if `*e` is nil"}}}})
