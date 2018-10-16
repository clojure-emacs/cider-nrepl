(ns cider.nrepl.print-method
  "Extending `print-method` defined in clojure.core, to provide
  prettier versions of some objects. This applies to anything that
  calls `print-method`, which includes return values, `pr`, `print`
  and the likes."
  (:require
   [clojure.main :as main])
  (:import
   [clojure.lang AFunction Atom MultiFn Namespace]
   java.io.Writer))

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

(defn- translate-class-name [c]
  (main/demunge (.getName (class c))))

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
  (translate-class-name c)
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
         (translate-class-name c)))
  ;; MultiFn names are not unique so we keep the identity HashCode to
  ;; make sure it's unique.
  (format " 0x%x]" (System/identityHashCode c)))

;;; Namespaces
;; Ex: #namespace[clojure.core]
(def-print-method Namespace c
  "#namespace["
  (format "%s" (ns-name c))
  "]")

;;; Agents, futures, delays, promises, etc
(defn- deref-name [c]
  (let [class-name (translate-class-name c)]
    (if-let [[_ short-name] (re-find #"^clojure\.lang\.([^.]+)" class-name)]
      (.toLowerCase short-name)
      (case (second (re-find #"^clojure\.core/(.+)/reify" class-name))
        "future-call" "future"
        "promise"     "promise"
        nil           class-name))))

;; `deref-as-map` is a private function, so let's be careful.
(when-let [f (resolve 'clojure.core/deref-as-map)]
  (def-print-method clojure.lang.IDeref c
    "#" (deref-name c) "["
    (pr-str (f c))
    (format " 0x%x]" (System/identityHashCode c))))
