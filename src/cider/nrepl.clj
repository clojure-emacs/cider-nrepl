(ns cider.nrepl
  (:require
   [cider.nrepl.version :as version]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.middleware.pprint :as pprint]
   [cider.nrepl.print-method]))

;; Compatibility with the legacy tools.nrepl and the new nREPL 0.4.x.
;; The assumption is that if someone is using old lein repl or boot repl
;; they'll end up using the tools.nrepl, otherwise the modern one.
(if (find-ns 'clojure.tools.nrepl)
  (require
   '[clojure.tools.nrepl.middleware :refer [set-descriptor!]]
   '[clojure.tools.nrepl.middleware.session :refer [session]]
   '[clojure.tools.nrepl.middleware.pr-values :refer [pr-values]]
   '[clojure.tools.nrepl.server :as nrepl-server])
  (require
   '[nrepl.middleware :refer [set-descriptor!]]
   '[nrepl.middleware.session :refer [session]]
   '[nrepl.middleware.pr-values :refer [pr-values]]
   '[nrepl.server :as nrepl-server]))

(def delayed-handlers
  "Map of `delay`s holding deferred middleware handlers."
  (atom {}))

(def require-lock
  "Lock used to inhibit concurrent `require` of the middleware namespaces.
  Clojure seem to have issues with concurrent loading of transitive
  dependencies. The issue is extremely hard to reproduce. For the context see
  https://github.com/clojure-emacs/cider/issues/2092 and
  https://github.com/clojure-emacs/cider/pull/2078."
  (Object.))

(defn- resolve-or-fail [sym]
  (or (resolve sym)
      (throw (IllegalArgumentException. (format "Cannot resolve %s" sym)))))

(defmacro run-deferred-handler
  "Make a delay out of `fn-name` and place it in `delayed-handlers` atom at compile time.
  Require and invoke the delay at run-time with arguments `handler` and
  `msg`. `fn-name` must be a namespaced symbol (unquoted)."
  [fn-name handler msg]
  (let [ns  (symbol (namespace `~fn-name))
        sym (symbol (name `~fn-name))]
    (swap! delayed-handlers assoc sym
           (delay
            (locking require-lock
              (require `~ns)
              (resolve-or-fail `~fn-name))))
    `(@(get @delayed-handlers '~sym) ~handler ~msg)))

(defmacro ^{:arglists '([name handler-fn descriptor]
                        [name handler-fn trigger-it descriptor])}
  def-wrapper
  "Define delayed middleware (e.g. wrap-xyz).
  `handler-fn` is an unquoted name of a function that takes two arguments -
  `handler` and `message`. It is called only when certain conditions are met as
  expressed by the optional `trigger-it` argument. `trigger-it` can be either a
  function or a set of ops (strings). When a function, it must take a `msg` and
  return truthy value when `handler-fn` should run. When `trigger-it` is missing,
  `handle-fn` is called when :op of `msg` is one of keys of the :handles slot of
  the `descriptor`. When `trigger-it` is a set it should contain extra ops,
  besides those in :handles slot, on which `handle-fn` is
  triggered. `descriptor` is passed directly to the nREPLs `set-descriptor!`."
  [name handler-fn & [trigger-it descriptor]]
  (let [[descriptor trigger-it] (if descriptor
                                  [descriptor trigger-it]
                                  [trigger-it descriptor])
        trigger-it (eval trigger-it)
        descriptor (eval descriptor)
        cond (if (or (nil? trigger-it) (set? trigger-it))
               (let [ops-set (into (-> descriptor :handles keys set) trigger-it)]
                 `(~ops-set (:op ~'msg)))
               `(~trigger-it ~'msg))
        doc (or (:doc descriptor) "")]
    (assert descriptor)
    `(do
       (defn ~name ~doc [~'h]
         (fn [~'msg]
           (if (and ~cond (not (:inhibit-cider-middleware ~'msg)))
             (run-deferred-handler ~handler-fn ~'h ~'msg)
             (~'h ~'msg))))
       (set-descriptor! #'~name ~descriptor))))

;;; Deferred Middleware Wrappers
;; Each middleware is defined in its own namespace, but here we're defining
;; deferred versions of the middleware handlers, that load the actual middleware
;; handlers on demand (the first time some middleware op gets invoked). This
;; makes the code a bit more complex, but improves a lot the startup time
;; as almost nothing gets loaded during REPL boot time.

(def wrap-pprint-fn-optional-arguments
  "Common pprint arguments for CIDER's middleware."
  {"pprint-fn" "The namespace-qualified name of a single-arity function to use for pretty-printing. Defaults to `clojure.pprint/pprint`."
   "print-length" "Value to bind to `*print-length*` when pretty-printing. Defaults to the value bound in the current REPL session."
   "print-level" "Value to bind to `*print-level*` when pretty-printing. Defaults to the value bound in the current REPL session."
   "print-meta" "Value to bind to `*print-meta*` when pretty-printing. Defaults to the value bound in the current REPL session."
   "print-right-margin" "Value to bind to `clojure.pprint/*print-right-margin*` when pretty-printing. Defaults to the value bound in the current REPL session."})

(def-wrapper wrap-diff-backend cider.nrepl.middleware.test/handle-diff-backend
  (fn [msg] true)
  {:doc "Middleware that adds the diff backend to be used for producing the diff of expected vs actual in tests.
Valid options are `:data-diff` to use `clojure.data/diff` and :deep-diff to use `lambdaisland.deep-diff/diff."
   :requires #{#'session}})

(def-wrapper wrap-pprint-fn cider.nrepl.middleware.pprint/handle-pprint-fn
  (fn [msg] true)
  {:doc "Middleware that provides a common interface for other middlewares that
         need to perform customisable pretty-printing.

         A namespace-qualified name of the function to be used for printing can
         be optionally passed in the `:pprint-fn` slot, the default value being
         `clojure.pprint/pprint`.

         The `:pprint-fn` slot will be replaced with a closure that calls the
         given printing function with `*print-length*`, `*print-level*`,
         `*print-meta*`, and `clojure.pprint/*print-right-margin*` bound to the
         values of the `:print-length`, `:print-level`, `:print-meta`, and
         `:print-right-margin` slots respectively.

         Middlewares further down the stack can then look up the `:pprint-fn`
         slot and call it where necessary."
   :requires #{#'session}})

(def-wrapper wrap-pprint cider.nrepl.middleware.pprint/handle-pprint
  #{"eval" "load-file"}
  (cljs/expects-piggieback
   {:doc "Middleware that adds a pretty-printing option to the eval op.
           Passing a non-nil value in the `:pprint` slot will cause eval to call
           clojure.pprint/pprint on its result. The `:right-margin` slot can be
           used to bind `*clojure.pprint/*print-right-margin*` during the
           evaluation. (N.B., the encoding used to transmit the request map
           `msg` across the wire will convert presumably falsey values into
           truthy values. If you don't want something to be pretty printed,
           remove the `:pprint` key entirely from your request map, don't try
           and set the value to nil, false, or string representations of the
           above)."
    :requires #{"clone" #'pr-values #'wrap-pprint-fn}
    :expects #{"eval" "load-file"}
    :handles {"pprint-middleware"
              {:doc "Enhances the `eval` op by adding pretty-printing. Not an op in itself."
               :optional (merge wrap-pprint-fn-optional-arguments
                                {"pprint" "If present and non-nil, pretty-print the result of evaluation."})}}}))

(def-wrapper wrap-content-type cider.nrepl.middleware.content-type/handle-content-type
  #{"eval"}
  {:doc "Middleware that adds `content-type` annotations to the result of the the eval op."
   :expects #{"eval" "load-file"}
   :returns {"content-type" "A MIME type for the response, if one can be detected."
             "content-transfer-encoding" "The encoding (if any) of the content."
             "body" "The content."}
   :handles {"content-type-middleware"
             {:doc "Enhances the `eval` op by adding `content-type` and friends to some responses. Not an op in itself."
              :optional {"content-type" "If present and non-nil, try to detect and handle content-types."}}}})

(def-wrapper wrap-slurp cider.nrepl.middleware.slurp/handle-slurp
  {:doc "Middleware that handles slurp requests."
   :returns {"content-type" "A MIME type for the response, if one can be detected."
             "content-transfer-encoding" "The encoding (if any) for the content."
             "body" "The slurped content body."}
   :handles {"slurp"
             {:doc "Slurps a URL from the nREPL server, returning MIME data."}}})

(def-wrapper wrap-apropos cider.nrepl.middleware.apropos/handle-apropos
  {:doc "Middleware that handles apropos requests"
   :handles {"apropos"
             {:doc "Return a sequence of vars whose name matches the query pattern, or if specified, having the pattern in their docstring."
              :requires {"query" "The search query."}
              :optional {"filter-regexps" "All vars from namespaces matching any regexp from this list would be dropped from the result."}
              :returns {"apropos-matches" "A list of matching symbols."}}}})

(def-wrapper wrap-classpath cider.nrepl.middleware.classpath/handle-classpath
  {:doc "Middleware that provides the java classpath."
   :handles {"classpath"
             {:doc "Obtain a list of entries in the Java classpath."
              :returns {"classpath" "A list of the Java classpath entries."}}}})

(def-wrapper wrap-complete cider.nrepl.middleware.complete/handle-complete
  (cljs/requires-piggieback
   {:doc "Middleware providing completion support."
    :requires #{#'session}
    :handles {"complete"
              {:doc "Return a list of symbols matching the specified (partial) symbol."
               :requires {"ns" "The symbol's namespace"
                          "symbol" "The symbol to lookup"
                          "session" "The current session"}
               :optional {"context" "Completion context for compliment."
                          "extra-metadata" "List of extra-metadata fields. Possible values: arglists, doc."}
               :returns {"completions" "A list of possible completions"}}
              "complete-doc"
              {:doc "Retrieve documentation suitable for display in completion popup"
               :requires {"ns" "The symbol's namespace"
                          "symbol" "The symbol to lookup"}
               :returns {"completion-doc" "Symbol's documentation"}}
              "complete-flush-caches"
              {:doc "Forces the completion backend to repopulate all its caches"}}}))

(def-wrapper wrap-debug cider.nrepl.middleware.debug/handle-debug
  #{"eval"}
  (cljs/requires-piggieback
   {:doc "Provide instrumentation and debugging functionality."
    :expects  #{"eval"}
    :requires #{#'wrap-pprint-fn #'session}
    :handles {"debug-input"
              {:doc "Read client input on debug action."
               :requires {"input" "The user's reply to the input request."}
               :returns  {"status" "done"}}
              "init-debugger"
              {:doc "Initialize the debugger so that `breakpoint` works correctly. This usually does not respond immediately. It sends a response when a breakpoint is reached or when the message is discarded."
               :requires {"id" "A message id that will be responded to when a breakpoint is reached."}}
              "debug-instrumented-defs"
              {:doc "Return an alist of definitions currently thought to be instrumented on each namespace. Due to Clojure's versatility, this could include false postives, but there will not be false negatives. Instrumentations inside protocols are not listed."
               :returns {"status" "done"
                         "list"   "The alist of (NAMESPACE . VARS) that are thought to be instrumented."}}
              "debug-middleware"
              {:doc "Debug a code form or fall back on regular eval."
               :requires {"id"    "A message id that will be responded to when a breakpoint is reached."
                          "code"  "Code to debug, there must be a #dbg or a #break reader macro in it, or nothing will happen."
                          "file"  "File where the code is located."
                          "ns"    "Passed to \"eval\"."
                          "point" "Position in the file where the provided code begins."}
               :returns {"status" "\"done\" if the message will no longer be used, or \"need-debug-input\" during debugging sessions"}}}}))

(def-wrapper wrap-enlighten cider.nrepl.middleware.enlighten/handle-enlighten
  :enlighten
  {:expects #{"eval" #'wrap-debug}})

(def-wrapper wrap-format cider.nrepl.middleware.format/handle-format
  {:doc "Middleware providing support for formatting Clojure code and EDN data."
   :requires #{#'wrap-pprint-fn}
   :handles {"format-code"
             {:doc "Reformats the given Clojure code, returning the result as a string."
              :requires {"code" "The code to format."}
              :optional {"options" "Configuration map for cljfmt."}
              :returns {"formatted-code" "The formatted code."}}
             "format-edn"
             {:doc "Reformats the given EDN data, returning the result as a string."
              :requires {"edn" "The data to format."}
              :optional {"print-right-margin" "The maximum column width of the formatted result."
                         "pprint-fn" "Fully qualified name of the print function to be used."}
              :returns {"formatted-edn" "The formatted data."}}}})

(def-wrapper wrap-info cider.nrepl.middleware.info/handle-info
  (cljs/requires-piggieback
   {:requires #{#'session}
    :handles {"info"
              {:doc "Return a map of information about the specified symbol."
               :requires {"symbol" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}
              "eldoc"
              {:doc "Return a map of information about the specified symbol."
               :requires {"symbol" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}
              "eldoc-datomic-query"
              {:doc "Return a map containing the inputs of the datomic query."
               :requires {"symbol" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}}}))

(def-wrapper wrap-inspect cider.nrepl.middleware.inspect/handle-inspect
  #{"eval"}
  (cljs/expects-piggieback
   {:doc "Add a value inspector option to the eval op. Passing a non-nil value
           in the `:inspect` slot will cause the last value returned by eval to
           be inspected. Returns a string representation of the resulting
           inspector's state in the `:value` slot."
    :requires #{"clone" #'pr-values}
    :expects #{"eval"}
    :handles {"inspect-pop"
              {:doc "Moves one level up in the inspector stack."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-push"
              {:doc "Inspects the inside value specified by index."
               :requires {"idx" "Index of the internal value currently rendered."
                          "session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-refresh"
              {:doc "Re-renders the currently inspected value."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-get-path"
              {:doc "Returns the path to the current position in the inspected value."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-next-page"
              {:doc "Jumps to the next page in paginated collection view."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-prev-page"
              {:doc "Jumps to the previous page in paginated collection view."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-set-page-size"
              {:doc "Sets the page size in paginated view to specified value."
               :requires {"page-size" "New page size."
                          "session" "The current session"}
               :returns {"status" "\"done\""}}}}))

(def-wrapper wrap-macroexpand cider.nrepl.middleware.macroexpand/handle-macroexpand
  (cljs/requires-piggieback
   {:doc "Macroexpansion middleware."
    :requires #{#'session}
    :handles {"macroexpand"
              {:doc "Produces macroexpansion of some form using the given expander."
               :requires {"code" "The form to macroexpand."}
               :optional {"ns" "The namespace in which to perform the macroexpansion. Defaults to 'user for Clojure and 'cljs.user for ClojureScript."
                          "expander" "The macroexpansion function to use. Possible values are \"macroexpand-1\", \"macroexpand\", or \"macroexpand-all\". Defaults to \"macroexpand\"."
                          "display-namespaces" "How to print namespace-qualified symbols in the result. Possible values are \"qualified\" to leave all namespaces qualified, \"none\" to elide all namespaces, or \"tidy\" to replace namespaces with their aliases in the given namespace. Defaults to \"qualified\"."
                          "print-meta" "If truthy, also print metadata of forms."}
               :returns {"expansion" "The macroexpanded form."}}}}))

(def-wrapper wrap-ns cider.nrepl.middleware.ns/handle-ns
  (cljs/requires-piggieback
   {:doc "Provide ns listing and browsing functionality."
    :requires #{#'session}
    :handles {"ns-list"
              {:doc "Return a sorted list of all namespaces."
               :returns {"status" "done" "ns-list" "The sorted list of all namespaces."}
               :optional {"filter-regexps" "All namespaces matching any regexp from this list would be dropped from the result."}}
              "ns-list-vars-by-name"
              {:doc "Return a list of vars named `name` amongst all namespaces."
               :requires {"name" "The name to use."}
               :returns {"status" "done" "var-list" "The list obtained."}}
              "ns-vars"
              {:doc "Returns a sorted list of all vars in a namespace."
               :requires {"ns" "The namespace to browse."}
               :returns {"status" "done" "ns-vars" "The sorted list of all vars in a namespace."}}
              "ns-vars-with-meta"
              {:doc "Returns a map of [var-name] to [var-metadata] for all vars in a namespace."
               :requires {"ns" "The namespace to use."}
               :returns {"status" "done" "ns-vars-with-meta" "The map of [var-name] to [var-metadata] for all vars in a namespace."}}
              "ns-path"
              {:doc "Returns the path to the file containing ns."
               :requires {"ns" "The namespace to find."}
               :return {"status" "done" "path" "The path to the file containing ns."}}
              "ns-load-all"
              {:doc "Loads all project namespaces."
               :return {"status" "done" "loaded-ns" "The list of ns that were loaded."}}
              "ns-aliases"
              {:doc "Returns a map of [ns-alias] to [ns-name] in a namespace."
               :requires {"ns" "The namespace to use."}
               :return {"status" "done" "ns-aliases" "The map of [ns-alias] to [ns-name] in a namespace."}}}}))

(def-wrapper wrap-out cider.nrepl.middleware.out/handle-out
  (cljs/expects-piggieback
   {:requires #{#'session}
    :expects #{"eval"}
    :handles {"out-subscribe"
              {:doc "Change #'*out* so that it also prints to active sessions, even outside an eval scope."}
              "out-unsubscribe"
              {:doc "Change #'*out* so that it no longer prints to active sessions outside an eval scope."}}}))

(def-wrapper wrap-profile cider.nrepl.middleware.profile/handle-profile
  {:doc     "Middleware that provides supports Profiling based on https://github.com/thunknyc/profile"
   :handles {"toggle-profile-ns"   {:doc      "Toggle profiling of given namespace."
                                    :requires {"ns" "The current namespace"}
                                    :returns  {"status" "Done"
                                               "value"  "'profiled' if profiling enabled, 'unprofiled' if disabled"}}
             "is-var-profiled"     {:doc      "Reports wheth symbol is currently profiled."
                                    :requires {"sym" "The symbol to check"
                                               "ns"  "The current namespace"}
                                    :returns  {"status" "Done"
                                               "value"  "'profiled' if profiling enabled, 'unprofiled' if disabled"}}
             "get-max-samples"     {:doc      "Returns maximum number of samples to be collected for any var."
                                    :requires {}
                                    :returns  {"status" "Done"
                                               "value"  "String representing number of max-sample-count"}}
             "set-max-samples"     {:doc      "Sets maximum sample count. Returns new max-sample-count."
                                    :requires {"max-samples" "Maxiumum samples to collect for any single var."}
                                    :returns  {"status" "Done"
                                               "value"  "String representing number of max-sample-count"}}
             "toggle-profile"      {:doc      "Toggle profiling of a given var."
                                    :requires {"sym" "The symbol to profile"
                                               "ns"  "The current namespace"}
                                    :returns  {"status" "Done"
                                               "value"  "'profiled' if profiling enabled, 'unprofiled' if disabled, 'unbound' if ns/sym not bound"}}
             "profile-var-summary" {:doc      "Return profiling data summary for a single var."
                                    :requires {"sym" "The symbol to profile"
                                               "ns"  "The current namespace"}
                                    :returns  {"status" "Done"
                                               "err"    "Content of profile summary report"}}
             "profile-summary"     {:doc      "Return profiling data summary."
                                    :requires {}
                                    :returns  {"status" "Done"
                                               "err"    "Content of profile summary report"}}
             "clear-profile"       {:doc      "Clears profile of samples."
                                    :requires {}
                                    :returns  {"status" "Done"}}}})

(def-wrapper wrap-refresh cider.nrepl.middleware.refresh/handle-refresh
  {:doc "Refresh middleware."
   :requires #{"clone" #'wrap-pprint-fn}
   :handles {"refresh"
             {:doc "Reloads all changed files in dependency order."
              :optional (merge wrap-pprint-fn-optional-arguments
                               {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
                                "before" "The namespace-qualified name of a zero-arity function to call before reloading."
                                "after" "The namespace-qualified name of a zero-arity function to call after reloading."})
              :returns {"reloading" "List of namespaces that will be reloaded."
                        "status" "`:ok` if reloading was successful; otherwise `:error`."
                        "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
                        "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}
             "refresh-all"
             {:doc "Reloads all files in dependency order."
              :optional (merge wrap-pprint-fn-optional-arguments
                               {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
                                "before" "The namespace-qualified name of a zero-arity function to call before reloading."
                                "after" "The namespace-qualified name of a zero-arity function to call after reloading."})
              :returns {"reloading" "List of namespaces that will be reloaded."
                        "status" "`:ok` if reloading was successful; otherwise `:error`."
                        "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
                        "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}
             "refresh-clear"
             {:doc "Clears the state of the refresh middleware. This can help recover from a failed load or a circular dependency error."}}})

(def-wrapper wrap-resource cider.nrepl.middleware.resource/handle-resource
  {:doc "Middleware that provides the path to resource."
   :handles {"resource"
             {:doc "Obtain the path to a resource."
              :requires {"name" "The name of the resource in question."}
              :returns {"resource-path" "The file path to a resource."}}
             "resources-list"
             {:doc "Obtain a list of all resources on the classpath."
              :returns {"resources-list" "The list of resources."}
              :optional {"context" "Completion context for compliment."
                         "prefix" "Prefix to filter out resources."}}}})

(def-wrapper wrap-spec cider.nrepl.middleware.spec/handle-spec
  (cljs/requires-piggieback
   {:doc "Middleware that provides `clojure.spec` browsing functionality."
    :handles {"spec-list" {:doc "Return a sorted list of all specs in the registry"
                           :returns {"status" "done"
                                     "spec-list" "The sorted list of all specs in the registry with their descriptions"}
                           :optional {"filter-regex" "Only the specs that matches filter prefix regex will be returned "}}
              "spec-form" {:doc "Return the form of a given spec"
                           :requires {"spec-name" "The spec namespaced keyword we are looking for"}
                           :returns {"status" "done"
                                     "spec-form" "The spec form"}}
              "spec-example" {:doc "Return a string with a pretty printed example for a spec"
                              :requires {"spec-name" "The spec namespaced keyword we want the example for"}
                              :returns {"status" "done"
                                        "example" "The pretty printed spec example string"}}}}))

(def-wrapper wrap-stacktrace cider.nrepl.middleware.stacktrace/handle-stacktrace
  (cljs/requires-piggieback
   {:doc "Middleware that handles stacktrace requests, sending
           cause and stack frame info for the most recent exception."
    :requires #{#'session #'wrap-pprint-fn}
    :expects #{}
    :handles {"stacktrace" {:doc "Return messages describing each cause and stack frame of the most recent exception."
                            :optional wrap-pprint-fn-optional-arguments
                            :returns {"status" "\"done\", or \"no-error\" if `*e` is nil"}}}}))

(def-wrapper wrap-test cider.nrepl.middleware.test/handle-test
  {:doc "Middleware that handles testing requests."
   :requires #{#'session #'wrap-pprint-fn #'wrap-diff-backend}
   :expects #{#'pr-values}
   :handles {"test-var-query"
             {:doc "Run tests specified by the `var-query` and return results. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-pprint-fn-optional-arguments}
             "test"
             {:doc "[DEPRECATED] Run tests in the specified namespace and return results. This accepts a set of `tests` to be run; if nil, runs all tests. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-pprint-fn-optional-arguments}
             "test-all"
             {:doc "[DEPRECATED] Run all tests in the project. If `load?` is truthy, all project namespaces are loaded; otherwise, only tests in presently loaded namespaces are run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-pprint-fn-optional-arguments}
             "test-stacktrace"
             {:doc "Rerun all tests that did not pass when last run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-pprint-fn-optional-arguments}
             "retest"
             {:doc "Return exception cause and stack frame info for an erring test via the `stacktrace` middleware. The error to be retrieved is referenced by namespace, var name, and assertion index within the var."
              :optional wrap-pprint-fn-optional-arguments}}})

(def-wrapper wrap-trace cider.nrepl.middleware.trace/handle-trace
  {:doc     "Toggle tracing of a given var."
   :handles {"toggle-trace-var"
             {:doc      "Toggle tracing of a given var."
              :requires {"sym" "The symbol to trace"
                         "ns"  "The current namespace"}
              :returns  {"var-status" "The result of tracing operation"
                         "var-name"   "The fully-qualified name of the traced/untraced var"}}
             "toggle-trace-ns"
             {:doc      "Toggle tracing of a given ns."
              :requires {"ns" "The namespace to trace"}
              :returns  {"ns-status" "The result of tracing operation"}}}})

(def ops-that-can-eval
  "Set of nREPL ops that can lead to code being evaluated."
  #{"eval" "load-file" "refresh" "refresh-all" "refresh-clear"
    "toggle-trace-var" "toggle-trace-ns" "undef"})

(def-wrapper wrap-tracker cider.nrepl.middleware.track-state/handle-tracker
  ops-that-can-eval
  (cljs/expects-piggieback
   {:doc "Enhances the `eval` op by notifying the client of the current REPL
           state. Currently, only the REPL type (Clojure or ClojureScript) is
           informed."
    :requires #{#'session}
    :expects ops-that-can-eval
    :handles {"track-state-middleware" ;; <- not handled !?
              {}}}))

(def-wrapper wrap-undef cider.nrepl.middleware.undef/handle-undef
  {:doc "Middleware to undefine a symbol in a namespace."
   :handles
   {"undef" {:doc "Undefine a symbol"
             :requires {"symbol" "The symbol to undefine"
                        "ns" "The current namespace"}
             :returns {"status" "done"}}}})

(def-wrapper wrap-version cider.nrepl.middleware.version/handle-version
  {:doc "Provides CIDER-nREPL version information."
   :describe-fn #'version/cider-version-reply ;; For the "describe" op. Merged into `:aux`.
   :handles
   {"cider-version"
    {:doc "Returns the version of the CIDER-nREPL middleware."
     :requires {}
     :returns {"cider-version" "CIDER-nREPL's version map."
               "status" "done"}}}})
;;; Cider's Handler

(def cider-middleware
  "A vector of all CIDER middleware."
  `[wrap-apropos
    wrap-classpath
    wrap-complete
    wrap-debug
    wrap-enlighten
    wrap-format
    wrap-info
    wrap-inspect
    wrap-macroexpand
    wrap-slurp
    wrap-ns
    wrap-out
    wrap-content-type
    wrap-slurp
    wrap-pprint
    wrap-diff-backend
    wrap-pprint-fn
    wrap-profile
    wrap-refresh
    wrap-resource
    wrap-spec
    wrap-stacktrace
    wrap-test
    wrap-trace
    wrap-tracker
    wrap-undef
    wrap-version])

(def cider-nrepl-handler
  "CIDER's nREPL handler."
  (apply nrepl-server/default-handler (map resolve-or-fail cider-middleware)))
