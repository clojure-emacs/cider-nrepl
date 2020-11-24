(ns cider.nrepl
  "Middleware descriptors and related utility functions.

  While normally middleware descriptors live alongside middleware
  definitions, cider-nrepl separates those. The rationale behind this
  is to avoid loading each middleware definition until its first usage.
  For this purpose we're defining each middleware as a wrapper that
  defers the loading of the actual middleware."
  (:require
   [cider.nrepl.version :as version]
   [cider.nrepl.middleware :as mw]
   [cider.nrepl.middleware.util.cljs :as cljs]
   [cider.nrepl.print-method] ;; we load this namespace, so it's directly available to clients
   [nrepl.middleware :refer [set-descriptor!]]
   [nrepl.middleware.caught :refer [wrap-caught]]
   [nrepl.middleware.print :refer [wrap-print wrap-print-optional-arguments]]
   [nrepl.middleware.session :refer [session]]
   [nrepl.server :as nrepl-server]))

;;; Functionality for deferred middleware loading
;;
;; cider-nrepl depends on many libraries and loading all middleware at
;; startup time causes significant delays. That's why we've developed
;; a simple approach to delay loading the actual definition of a middleware
;; until a request handled by this middleware is made.

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

(defn- handler-future
  "Check whether a delay exists in the `delayed-handlers`. Otherwise make a delay
  out of `fn-name` and place it in the atom. "
  [sym ns fn-name]
  (or (get @delayed-handlers sym)
      (get (swap! delayed-handlers assoc sym
                  (delay
                    (locking require-lock
                      (require ns)
                      (resolve-or-fail fn-name))))
           sym)))

(defmacro run-deferred-handler
  "Require and invoke the handler delay at run-time with arguments `handler` and `msg`.
  `fn-name` must be a namespaced symbol (unquoted)."
  [fn-name handler msg]
  (let [ns  (symbol (namespace `~fn-name))
        sym (symbol (name `~fn-name))]
    `(@(handler-future '~sym '~ns '~fn-name)
      ~handler ~msg)))


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
;;
;; Each middleware is defined in its own namespace, but here we're defining
;; deferred versions of the middleware handlers, that load the actual middleware
;; handlers on demand (the first time some middleware op gets invoked). This
;; makes the code a bit more complex, but improves a lot the startup time
;; as almost nothing gets loaded during REPL boot time.

(def-wrapper wrap-content-type cider.nrepl.middleware.content-type/handle-content-type
  #{"eval"}
  {:doc "Middleware that adds `content-type` annotations to the result of the the eval op."
   :requires #{#'wrap-print}
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
               :requires {"ns" "The namespace is which to look for completions (falls back to *ns* if not specified)"
                          "prefix" "The prefix for completion candidates"
                          "session" "The current session"}
               :optional {"context" "Completion context for compliment."
                          "extra-metadata" "List of extra-metadata fields. Possible values: arglists, doc."}
               :returns {"completions" "A list of possible completions"}}
              "complete-doc"
              {:doc "Retrieve documentation suitable for display in completion popup"
               :requires {"ns" "The symbol's namespace"
                          "sym" "The symbol to lookup"}
               :returns {"completion-doc" "Symbol's documentation"}}
              "complete-flush-caches"
              {:doc "Forces the completion backend to repopulate all its caches"}}}))

(def-wrapper wrap-debug cider.nrepl.middleware.debug/handle-debug
  #{"eval"}
  (cljs/requires-piggieback
   {:doc "Provide instrumentation and debugging functionality."
    :expects  #{"eval"}
    :requires #{#'wrap-print #'session}
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
   :requires #{#'wrap-print}
   :handles {"format-code"
             {:doc "Reformats the given Clojure code, returning the result as a string."
              :requires {"code" "The code to format."}
              :optional {"options" "Configuration map for cljfmt."}
              :returns {"formatted-code" "The formatted code."}}
             "format-edn"
             {:doc "Reformats the given EDN data, returning the result as a string."
              :requires {"edn" "The data to format."}
              :optional wrap-print-optional-arguments
              :returns {"formatted-edn" "The formatted data."}}}})

(def-wrapper wrap-info cider.nrepl.middleware.info/handle-info
  (cljs/requires-piggieback
   {:requires #{#'session}
    :handles {"info"
              {:doc "Return a map of information about the specified symbol."
               :requires {"sym" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}
              "eldoc"
              {:doc "Return a map of information about the specified symbol."
               :requires {"sym" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}
              "eldoc-datomic-query"
              {:doc "Return a map containing the inputs of the datomic query."
               :requires {"sym" "The symbol to lookup"
                          "ns" "The current namespace"}
               :returns {"status" "done"}}}}))

(def-wrapper wrap-inspect cider.nrepl.middleware.inspect/handle-inspect
  #{"eval"}
  (cljs/expects-piggieback
   {:doc "Add a value inspector option to the eval op. Passing a non-nil value
           in the `:inspect` slot will cause the last value returned by eval to
           be inspected. Returns a string representation of the resulting
           inspector's state in the `:value` slot."
    :requires #{"clone" #'wrap-caught #'wrap-print}
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
               :returns {"status" "\"done\""}}
              "inspect-clear"
              {:doc "Clears the state state of the inspector."
               :requires {"session" "The current session"}
               :returns {"status" "\"done\""}}
              "inspect-def-current-value"
              {:doc "Define the currently inspected value as a var with the given var-name in the provided namespace."
               :requires {"session" "The current session"
                          "ns" "Namespace to define var on"
                          "var-name" "The var name"}
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
              {:doc "Returns a sorted list of public vars in a namespace."
               :requires {"ns" "The namespace to browse."}
               :optional {"var-query" "The search query for vars. Only \"private?\" is supported for ClojureScript."}
               :returns {"status" "done" "ns-vars" "The sorted list of public vars in a namespace."}}
              "ns-vars-with-meta"
              {:doc "Returns a map of [var-name] to [var-metadata] for public vars in a namespace."
               :requires {"ns" "The namespace to use."}
               :optional {"var-query" "The search query for vars. Only \"private?\" is supported for ClojureScript."}
               :returns {"status" "done" "ns-vars-with-meta" "The map of [var-name] to [var-metadata] for public vars in a namespace."}}
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
   :requires #{"clone" #'wrap-print}
   :handles {"refresh"
             {:doc "Reloads all changed files in dependency order."
              :optional (merge wrap-print-optional-arguments
                               {"dirs" "List of directories to scan. If no directories given, defaults to all directories on the classpath."
                                "before" "The namespace-qualified name of a zero-arity function to call before reloading."
                                "after" "The namespace-qualified name of a zero-arity function to call after reloading."})
              :returns {"reloading" "List of namespaces that will be reloaded."
                        "status" "`:ok` if reloading was successful; otherwise `:error`."
                        "error" "A sequence of all causes of the thrown exception when `status` is `:error`."
                        "error-ns" "The namespace that caused reloading to fail when `status` is `:error`."}}
             "refresh-all"
             {:doc "Reloads all files in dependency order."
              :optional (merge wrap-print-optional-arguments
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
              :returns {"resources-list" "The list of resources."}}}})

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
    :requires #{#'session #'wrap-print}
    :expects #{}
    :handles {"stacktrace" {:doc "Return messages describing each cause and stack frame of the most recent exception."
                            :optional wrap-print-optional-arguments
                            :returns {"status" "\"done\", or \"no-error\" if `*e` is nil"}}}}))

(def-wrapper wrap-test cider.nrepl.middleware.test/handle-test
  {:doc "Middleware that handles testing requests."
   :requires #{#'session #'wrap-print}
   :handles {"test-var-query"
             {:doc "Run tests specified by the `var-query` and return results. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :requires {"var-query" "A search query specifying the test vars to execute. See Orchard's var query documentation for more details."}
              :optional wrap-print-optional-arguments
              :returns {"results" "A map of test run data."
                        "status" "Either done or indication of an error"}}
             "test"
             {:doc "[DEPRECATED] Run tests in the specified namespace and return results. This accepts a set of `tests` to be run; if nil, runs all tests. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-print-optional-arguments}
             "test-all"
             {:doc "[DEPRECATED] Run all tests in the project. If `load?` is truthy, all project namespaces are loaded; otherwise, only tests in presently loaded namespaces are run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-print-optional-arguments}
             "test-stacktrace"
             {:doc "Rerun all tests that did not pass when last run. Results are cached for exception retrieval and to enable re-running of failed/erring tests."
              :optional wrap-print-optional-arguments}
             "retest"
             {:doc "Return exception cause and stack frame info for an erring test via the `stacktrace` middleware. The error to be retrieved is referenced by namespace, var name, and assertion index within the var."
              :optional wrap-print-optional-arguments}}})

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
             :requires {"sym" "The symbol to undefine"
                        "ns" "The namespace is which to resolve sym (falls back to *ns* if not specified)"}
             :returns {"status" "done"}}}})

(def-wrapper wrap-version cider.nrepl.middleware.version/handle-version
  {:doc "Provides CIDER-nREPL version information."
   :describe-fn (fn [_] {:cider-version version/version}) ;; For the "describe" op. Merged into `:aux`.
   :handles
   {"cider-version"
    {:doc "Returns the version of the CIDER-nREPL middleware."
     :requires {}
     :returns {"cider-version" "CIDER-nREPL's version map."
               "status" "done"}}}})

(def-wrapper wrap-xref cider.nrepl.middleware.xref/handle-xref
  {:doc "Middleware that provides find references functionality."
   :handles {"fn-refs"
             {:doc "Look up functions that reference a particular function."
              :requires {"sym" "The symbol to lookup"
                         "ns" "The current namespace"}
              :returns {"fn-refs" "A list of function references."
                        "status" "done"}}
             "fn-deps"
             {:doc "Look up the function dependencies of particular function."
              :requires {"sym" "The symbol to lookup"
                         "ns" "The current namespace"}
              :returns {"fn-deps" "A list of function deps."
                        "status" "done"}}}})

(def-wrapper wrap-clojuredocs cider.nrepl.middleware.clojuredocs/handle-clojuredocs
  {:doc "Middleware to find a documents from ClojureDocs."
   :handles {"clojuredocs-refresh-cache"
             {:doc "Reload exported documents file from ClojureDocs, and store it as a cache."
              :requires {}
              :optional {"export-edn-url" "EDN file URL exported from ClojureDocs. Defaults to \"https://clojuredocs-edn.netlify.com/export.compact.edn\"."}
              :returns {"status" "\"ok\" if reloading was successful"}}
             "clojuredocs-lookup"
             {:doc "Return a map of information in ClojureDocs."
              :requires {"ns" "The namespace where `sym` will be resolved."
                         "sym" "The symbol to lookup."}
              :returns {"clojuredocs" "A map of information in ClojureDocs."
                        "status" "\"no-doc\" if there is no document matching to `ns` and `symbol`."}}}})

;;; CIDER's nREPL Handler
;;
;; Here everything comes together. We define an nREPL handler
;; that includes all of CIDER's middleware. Note that
;; end users might opt to build custom handlers that don't
;; include every middleware available.

(def cider-middleware mw/cider-middleware) ;; for backwards compatibility

(def cider-nrepl-handler
  "CIDER's nREPL handler."
  (apply nrepl-server/default-handler (map resolve-or-fail mw/cider-middleware)))
