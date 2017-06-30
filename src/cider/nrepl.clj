(ns cider.nrepl
  (:require [clojure.tools.nrepl.server :as nrepl-server]
            [cider.nrepl.print-method]
            [cider.nrepl.middleware.apropos]
            [cider.nrepl.middleware.classpath]
            [cider.nrepl.middleware.complete]
            [cider.nrepl.middleware.debug]
            [cider.nrepl.middleware.enlighten]
            [cider.nrepl.middleware.format]
            [cider.nrepl.middleware.info]
            [cider.nrepl.middleware.inspect]
            [cider.nrepl.middleware.macroexpand]
            [cider.nrepl.middleware.ns]
            [cider.nrepl.middleware.spec]
            [cider.nrepl.middleware.out]
            [cider.nrepl.middleware.pprint]
            [cider.nrepl.middleware.refresh]
            [cider.nrepl.middleware.resource]
            [cider.nrepl.middleware.stacktrace]
            [cider.nrepl.middleware.test]
            [cider.nrepl.middleware.trace]
            [cider.nrepl.middleware.track-state]
            [cider.nrepl.middleware.undef]
            [cider.nrepl.middleware.version]))

(def cider-middleware
  "A vector containing all CIDER middleware."
  '[cider.nrepl.middleware.apropos/wrap-apropos
    cider.nrepl.middleware.classpath/wrap-classpath
    cider.nrepl.middleware.complete/wrap-complete
    cider.nrepl.middleware.debug/wrap-debug
    cider.nrepl.middleware.enlighten/wrap-enlighten
    cider.nrepl.middleware.format/wrap-format
    cider.nrepl.middleware.info/wrap-info
    cider.nrepl.middleware.inspect/wrap-inspect
    cider.nrepl.middleware.macroexpand/wrap-macroexpand
    cider.nrepl.middleware.spec/wrap-spec
    cider.nrepl.middleware.ns/wrap-ns
    cider.nrepl.middleware.out/wrap-out
    cider.nrepl.middleware.pprint/wrap-pprint
    cider.nrepl.middleware.pprint/wrap-pprint-fn
    cider.nrepl.middleware.refresh/wrap-refresh
    cider.nrepl.middleware.resource/wrap-resource
    cider.nrepl.middleware.stacktrace/wrap-stacktrace
    cider.nrepl.middleware.test/wrap-test
    cider.nrepl.middleware.trace/wrap-trace
    cider.nrepl.middleware.track-state/wrap-tracker
    cider.nrepl.middleware.undef/wrap-undef
    cider.nrepl.middleware.version/wrap-version])

(def cider-nrepl-handler
  "CIDER's nREPL handler."
  (apply nrepl-server/default-handler (map resolve cider-middleware)))
