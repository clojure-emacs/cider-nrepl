(ns cider.nrepl.print-method
  "Extending `print-method` defined in clojure.core, to provide
  prettier versions of some objects. This applies to anything that
  calls `print-method`, which includes return values, `pr`, `print`
  and the likes."
  (:require
   [orchard.print :as print])
  (:import
   (clojure.lang AFunction Atom IDeref MultiFn Namespace)))

(def ^:dynamic *pretty-objects*
  "If true, cider prettifies some object descriptions.
  For instance, instead of printing functions as
      #object[clojure.core$_PLUS_ 0x4e648e99 \"clojure.core$_PLUS_@4e648e99\"]
  they are printed as
      #function[clojure.core/+]

  To disable this feature, do
      (alter-var-root #'cider.nrepl.print-method/*pretty-objects* not)"
  true)

(defn- replace-with-orchard-print
  "Replace `clojure.core/print-method` for the given class with
  `orchard.print/print` when `*pretty-objects*` is true, otherwise call the
  default Clojure implementation."
  [klass]
  (defmethod print-method klass [x writer]
    (if *pretty-objects*
      (print/print x writer)
      (#'clojure.core/print-object writer))))

;; NB: we don't replace all clojure.core/print-method implementations with
;; orchard.print/print because they arguably have different purpose. Orchard
;; printer is more human-oriented whereas print-method is a bit more
;; machine-oriented. So, we only replace it for those types where the visual
;; improvement is substantial yet we are confident it won't break something.

;;; Atoms: #atom[{:foo :bar}]
(replace-with-orchard-print Atom)

;;; Function objects: #function[clojure.core/str]
(replace-with-orchard-print AFunction)

;;; Multimethods: #multifn[print-method 0x3f0cd5b4]
(replace-with-orchard-print MultiFn)

;;; Namespaces: #namespace[clojure.core]
(replace-with-orchard-print Namespace)

;;; Various derefables
;; #agent[1], #agent[<failed> #error[...]]
;; #delay[<pending>], #delay[1], #delay[<failed> #error[...]]
;; #future[<pending>], #future[1], #future[<failed> #error[...]]
;; #promise[<pending>], #promise[1]
(replace-with-orchard-print IDeref)
