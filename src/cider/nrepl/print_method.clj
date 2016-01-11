(ns cider.nrepl.print-method
  (:require [clojure.string :as s])
  (:import [clojure.lang AFunction Atom MultiFn Namespace]
           java.io.Writer))

;; Extending `print-method` defined in clojure.core, to provide
;; prettier versions of some objects. This applies to anything that
;; calls `print-method`, which includes return values, `pr`, `print`
;; and the likes.

(def ^:dynamic *pretty-objects*
  "If true, cider prettifies some object descriptions.
  For instance, instead of printing functions as
      #object[clojure.core$_PLUS_ 0x4e648e99 \"clojure.core$_PLUS_@4e648e99\"]
  they are printed as
      #function[clojure.core/+]

  To disable this feature, do
      (alter-var-root #'cider.nrepl.print-method/*pretty-objects* not)"
  true)

(defmacro def-print-method [dispatch-val arg & strings]
  `(defmethod print-method ~dispatch-val [~arg ~'^Writer w]
     (if *pretty-objects*
       (do ~@(map #(list '.write 'w %) strings))
       (#'clojure.core/print-object ~arg ~'w))))

;;; Atoms
;; Ex: #atom[{:foo :bar} 0x54274a2b]
(def-print-method Atom c
  "#atom["
  (pr-str @c)
  (format " 0x%x]" (System/identityHashCode c)))

;;; Function objects
;; Ex: #function[cider.nrepl.print-method/multifn-name]
(def-print-method AFunction c
  "#function["
  (-> (.getName (class c))
      (s/replace-first "$" "/")
      (s/replace "_QMARK_" "?")
      (s/replace "_PLUS_" "+")
      (s/replace "_BANG_" "!")
      (s/replace "_EQ_" "=")
      (s/replace "_SLASH_" "/")
      (s/replace "_STAR_" "*")
      (s/replace "_" "-"))
  "]")

;;; Multimethods
;; Ex: #multifn[print-method 0x3f0cd5b4]
(defn multifn-name [^MultiFn mfn]
  (let [field (.getDeclaredField MultiFn "name")
        private (not (.isAccessible field))]
    (when private
      (.setAccessible field true))
    (let [name (.get field mfn)]
      (when private
        (.setAccessible field false))
      name)))

(def-print-method MultiFn c
  "#multifn["
  (try (multifn-name c)
       (catch SecurityException _
         (class c)))
  ;; MultiFn names are not unique so we keep the identity HashCode to
  ;; make sure it's unique.
  (format " 0x%x]" (System/identityHashCode c)))

;;; Namespaces
;; Ex: #namespace[clojure.core]
(def-print-method Namespace c
  "#namespace["
  (format "%s" (ns-name c))
  "]")
