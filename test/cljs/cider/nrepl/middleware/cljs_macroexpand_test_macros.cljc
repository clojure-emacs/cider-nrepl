(ns cider.nrepl.middleware.cljs-macroexpand-test-macros)

;; A user-defined macro used to verify that ClojureScript macroexpansion works
;; for macros that aren't part of cljs.core (see
;; https://github.com/clojure-emacs/cider/issues/2099).

(defmacro my-when
  [test & body]
  `(if ~test (do ~@body)))
