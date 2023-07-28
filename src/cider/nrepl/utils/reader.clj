(ns cider.nrepl.utils.reader
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.reader :as reader]
   [clojure.tools.reader.reader-types :as reader-types])
  (:import
   (clojure.lang Namespace)))

(defn impl [resource line column current-ns]
  (with-open [reader (io/reader resource)]
    (let [push-back-reader (reader-types/push-back-reader reader)
          line-reader (reader-types/indexing-push-back-reader push-back-reader)]

      (while (< (reader-types/get-line-number line-reader) line)
        (reader-types/read-line line-reader))

      (while (< (reader-types/get-column-number line-reader) column)
        (reader-types/read-char line-reader))

      (binding [reader/*alias-map* (ns-aliases current-ns)
                reader/*data-readers* *data-readers*
                reader/*read-eval* false]
        (reader/read {:read-cond :allow
                      :feature #{:clj}}
                     push-back-reader)))))

(def memoized-impl (memoize impl))

(defn read-form-at
  "Reads a Clojure form from a file at a specific line and column."
  [{:keys [file line column]} ^Namespace current-ns]
  (let [column (or column 1)]
    (when (and (not-empty file)
               line)
      (when-let [resource (io/resource file)]
        (let [jar? (-> resource str (string/starts-with? "jar:"))
              f (if jar? ;; jars are immutable and therefore their content is safe to memoize.
                  memoized-impl
                  impl)]
          (f resource line column current-ns))))))
