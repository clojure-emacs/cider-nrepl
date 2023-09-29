# Changelog

## master (unreleased)

* Bump `haystack` to [0.3.1](https://github.com/clojure-emacs/haystack/blob/v0.3.1/CHANGELOG.md#031-2023-09-29).
* Cache the last result of internal Haystack (exception analysis) calls.

### Changes

### Bugs Fixed

* `info` and `eldoc` ops: fix regression for the special form `..`.

## 0.38.1 (2023-09-21)

* Bump `orchard` to [1.5.1](https://github.com/clojure-emacs/orchard/blob/v0.15.1/CHANGELOG.md#0151-2023-09-21).

## 0.38.0 (2023-09-21)

### Changes

* `info` and `eldoc` ops: also return `:doc-fragments`, `:doc-first-sentence-fragments`, `:doc-first-sentence-fragments` attributes when available.
  * These are explained in https://github.com/clojure-emacs/orchard/blob/v0.15.0/src-newer-jdks/orchard/java/parser_next.clj#L2-L20
  * These typically are only available when running a modern JDK through enrich-classpath.
* `info` and `eldoc` ops: accept a Compliment-style `context` parameter that helps infering the Java class of the object being queried.
* Bump `orchard` to [1.5.0](https://github.com/clojure-emacs/orchard/blob/v0.15.0/CHANGELOG.md#0150-2023-09-20).
* Bump `compliment` to [0.4.3](https://github.com/alexander-yakushev/compliment/blob/0.4.3/CHANGELOG.md#043-2023-09-21).

## 0.37.1 (2023-09-08)

### Bugs Fixed

* `middleware.track-state`: handle non-existing .jar files that may be in the classpath.

## 0.37.0 (2023-08-27)

### Changes

* `"ns-path"` op: also include a `:url` attribute in the response.

## 0.36.1 (2023-08-22)

### Changes

* Bump `compliment` to [0.4.1](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#041-2023-08-23).

## 0.36.0 (2023-08-21)

### Changes

* Bump `haystack` to [0.2.0](https://github.com/clojure-emacs/haystack/blob/0077b5c49f4aef1c7f89d5430d6dda2f9e7d78d4/CHANGELOG.md#020-2023-08-20).

## 0.35.1 (2023-08-18)

### Changes

* Bump `haystack` to [0.1.0](https://github.com/clojure-emacs/haystack/blob/v0.1.0/CHANGELOG.md#010).

## 0.35.0 (2023-08-09)

### New features

* Make the `track-state` middleware invokeable directly, by requesting the new `"cider/get-state"` op.
  * This makes it possible to access `:changed-namespaces` info on demand, which can be necessary for:
    * non-Piggieback based clojurescript repls
    * re-computing the ns info responding to external (non-nREPL triggered) events. 

### Changes

* [#796](https://github.com/clojure-emacs/cider-nrepl/issues/796): `fn-refs` and `fn-deps` middleware: add new `:file-url` field.
  * these are absolute and prefixed by `file:` or `file:jar:`, whenever possible.
* `fn-refs` and `fn-deps` middleware: sort the results by file, line and column.
* Bump `orchard` to [1.4.2](https://github.com/clojure-emacs/orchard/blob/v0.14.2/CHANGELOG.md#0142-2023-08-09).

## 0.34.0 (2023-08-03)

* Bump `orchard` to [1.4.0](https://github.com/clojure-emacs/orchard/blob/v0.14.0/CHANGELOG.md#0140-2023-08-03).

## 0.33.0 (2023-07-31)

### New features

* [#777](https://github.com/clojure-emacs/cider-nrepl/issues/777) `middleware.track-state`: now an inferred `:style/indent` metadata value is included, when adequate.

### Changes

* Bump `suitable` to [0.5.0](https://github.com/clojure-emacs/clj-suitable/blob/v0.5.0/CHANGELOG.md#050-2023-07-28).

### Bugs Fixed

* [CIDER #3385](https://github.com/clojure-emacs/cider/issues/3385): `track-state`: Include `cljs.core` ns info.

## 0.32.0 (2023-07-26)

### New features

* [#773](https://github.com/clojure-emacs/cider-nrepl/pull/773) Add middleware to capture, debug, inspect and view log events emitted by Java logging frameworks.
* [#755](https://github.com/clojure-emacs/cider-nrepl/pull/755) `middleware.test`: now timing information is returned at var and ns level under the `:ms`/`:humanized` keys. 
* `middleware.test`: only include `:diff` data when the expected/actual mismatch is deemed diffable.
  * i.e., maps, sets and sequences are diffable, scalar values are not.
* [#709](https://github.com/clojure-emacs/cider-nrepl/pull/709) `middleware.test`: offer fail-fast functionality.

### Changes

* Bump `orchard` to 0.13.0.
* Bump `compliment` to [0.4.0](https://github.com/alexander-yakushev/compliment/blob/0.4.0/CHANGELOG.md#040-2023-07-05).
* The `track-state` middleware now returns accurate metadata for cljs requests.

## 0.31.0 (2023-06-23)

### Changes

* Bump `cljfmt` to 0.9.2.
* Bump `puget` to 1.3.4.
* Bump `compliment` to 0.3.16.
* [#769](https://github.com/clojure-emacs/cider-nrepl/issues/769): Introduce new `#break!` and `#dbg!` reader macros for breaking on exception
* [#772](https://github.com/clojure-emacs/cider-nrepl/issues/772): Improve debugger heuristics on "interesting" breakpoints (Always break on forms tagged with #break, including vectors and maps)
* The `wrap-debug` middleware now adds an optional `:caught-msg` key to the `eval` op response, containing the exception message when caught by the debugger.

## 0.30.0 (2023-01-31)

### New features

* [#766](https://github.com/clojure-emacs/cider-nrepl/issues/766): Complete local bindings for ClojureScript files.

## 0.29.0 (2022-12-05)

### New features

* [#758](https://github.com/clojure-emacs/cider-nrepl/pull/758): Add nREPL op to parse stacktraces into data (`analyze-stacktrace`), rename `stacktrace` op to `analyze-last-stacktrace` and deprecate `stacktrace`.

### Changes

* Bump `puget` to 1.3.3.

## 0.28.7 (2022-10-25)

### Changes

* Bump `orchard` to 0.11.0 (this adds support for Spec 2).

## 0.28.6 (2022-09-19)

### Changes

* Bump `orchard` to version [0.10.0](https://github.com/clojure-emacs/orchard/releases/tag/v0.10.0).
* Bump `compliment` to version [0.3.14](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#0314-2022-07-11).
* Bump `fipp` to 0.6.26.
* Bump `cljfmt` to 0.9.0.

## 0.28.5 (2022-06-19)

### Changes

* Bump `compliment` to version [0.3.13](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#0313-2022-06-18).

## 0.28.4 (2022-05-18)

### Bugs Fixed

* [#751](https://github.com/clojure-emacs/cider-nrepl/issues/751): Skip unmapping of default imports in `undef-all` op.

### Changes

* Use `tools.namespace`[1.3.0](https://github.com/clojure/tools.namespace/compare/v1.2.0...v1.3.0).

## 0.28.3 (2022-02-22)

### Changes

* Upgrade [Orchard to version 0.9.2](https://github.com/clojure-emacs/orchard/blob/v0.9.2/CHANGELOG.md#092-2022-02-22).

## 0.28.2 (2022-02-01)

Upgrade `tools.trace`.

## 0.28.1 (2022-01-21)

### Changes

* Upgrade [Orchard to version 0.9.1](https://github.com/clojure-emacs/orchard/blob/v0.9.1/CHANGELOG.md#091-2022-01-17).

## 0.28.0 (2022-01-11)

### New features

* [#548](https://github.com/clojure-emacs/cider-nrepl/pull/548): Make the content-type middleware extensible via multimethod.

## Changes

* Upgrade [Orchard to version 0.9](https://github.com/clojure-emacs/orchard/releases/tag/v0.9.0).

## 0.27.4 (2021-12-15)

### Bugs fixed

* [#721](https://github.com/clojure-emacs/cider-nrepl/issues/721): `middleware.macroexpand`: support a corner case for tools.deps.
* [#735](https://github.com/clojure-emacs/cider-nrepl/issues/735): `middleware.test.extensions`: make `:actual` reporting clearer.
* [#737](https://github.com/clojure-emacs/cider-nrepl/pull/737): Fix a regression in `middleware.out` that could result in duplicate output.
* Update `orchard` to 0.8, which addresses a bunch of dynapath-related classloader issues. Navigation to Java sources should work more reliably now.

## 0.27.3 (2021-12-07)

### Bugs fixed

* [#733](https://github.com/clojure-emacs/cider-nrepl/issues/719): `middleware.out`: remove reflection.
* [#719](https://github.com/clojure-emacs/cider-nrepl/issues/719): `middleware.test`: gracefully handle exceptions thrown within fixtures.
* [#722](https://github.com/clojure-emacs/cider-nrepl/issues/722): `middleware.format`: print otherwise non-serializable objects as strings.
* [#708](https://github.com/clojure-emacs/cider-nrepl/issues/708): Upgrade Compliment, improving how autocompletion works in Windows.
* [#713](https://github.com/clojure-emacs/cider-nrepl/pull/713): Fix a regression in test results formatting.

## 0.27.2 (2021-10-03)

### Bugs fixed

* [#716](https://github.com/clojure-emacs/cider-nrepl/issues/716): Fix StackOverflowErrors related to `*out*` handling.

## 0.27.1 (2021-10-02)

### Bugs fixed

* Upgrade `suitable` and `orchard`, bringing in related bugfixes.

## 0.27.0 (2021-09-30)

### New features

* [#698](https://github.com/clojure-emacs/cider-nrepl/pull/698): Add `undef-all` op to undefine all symbols and aliases in namespace.
* Introduce `cider.nrepl.middleware.test/*test-error-handler*` var which you can override with arbitrary functions.

### Bugs fixed

* Make `middleware.stacktrace` detect a given project's common ns prefix even in face of single-segment namespaces such as `user`.

### Changes

* Parallelize `cider.nrepl.middleware.stacktrace/analyze-stacktrace`.
* [#705](https://github.com/clojure-emacs/cider-nrepl/pull/705): Use the session classloader when loading deferred middleware.

## 0.26.0 (2021-04-22)

### New features

* [#694](https://github.com/clojure-emacs/cider-nrepl/pull/694): [Inspector] Configure truncation limits.

### Bugs fixed

* Update `clj-suitable` to version 0.4 and re-enable enhanced completions for `shadow-cljs`.

## 0.25.11 (2021-04-12)

### Bugs Fixed

* [#695](https://github.com/clojure-emacs/cider-nrepl/pull/695): Fix debugger quit for http handler functions.

## 0.25.10 (2021-04-08)

### Bugs Fixed

* Enable `suitable`'s enhanced completions for anything but `shadow-cljs`.

## 0.25.9 (2021-02-13)

### Bugs Fixed

* [cider#2979](https://github.com/clojure-emacs/cider/issues/2979): Convert non-integer numbers to strings.
* Another fix for the dynamic classpath modification (in Orchard 0.6.5).

## 0.25.8 (2021-01-25)

### Bugs Fixed

* Make dynamic classpath modification robust to compiler loader binding (fixed in Orchard 0.6.4).

## 0.25.7 (2021-01-21)

### Bugs Fixed

* [#687](https://github.com/clojure-emacs/cider-nrepl/issues/687): Make sure that the Orchard JDK sources cache is visible in nREPL (the actual fix is in Orchard 0.6.3).

## 0.25.6 (2021-01-04)

### Bugs Fixed

* Fetch ClojureDocs data from updated location (fixed in Orchard 0.6.2).
* Fix compatibility with Java 15 (fixed in Orchard 0.6.2).

## 0.25.5 (2020-11-24)

### Bugs Fixed

* [#684](https://github.com/clojure-emacs/cider-nrepl/pull/684): Fix delayed middleware loading issue.
* [#683](https://github.com/clojure-emacs/cider-nrepl/pull/683): Support custom `print-method`s in test reports.

## 0.25.4 (2020-10-08)

### Changes

* Address reflection warnings in `compliment` and `orchard`.

## 0.25.3 (2020-07-31)

### Bugs fixed

* [#667](https://github.com/clojure-emacs/cider-nrepl/pull/667): Filter non file urls from classpath response.

### Changes

* [#679](https://github.com/clojure-emacs/cider-nrepl/pull/679): Update to use bundled ClojureDocs documents.

## 0.25.2 (2020-06-07)

### Bugs fixed

* Remove workaround for converting column numbers to 0-based indexing. ([Cider issue](https://github.com/clojure-emacs/cider/issues/2852))

## 0.25.1 (2020-06-04)

### Bugs fixed

* Bump Orchard to 0.5.10, as the 0.5.9 was a bad build. ([Orchard issue](https://github.com/clojure-emacs/orchard/issues/91))

## 0.25.0 (2020-05-30)

### New Features

* [#670](https://github.com/clojure-emacs/cider-nrepl/pull/670): Extend `undef` op to work on fully qualified symbols.

### Bugs fixed

* [#666](https://github.com/clojure-emacs/cider-nrepl/pull/666): Add a `nil` `:query` check in apropos's `var-query-map`.
* [#669](https://github.com/clojure-emacs/cider-nrepl/pull/669): Fix NPE and add `isDirectory` check in slurp middleware.
* [#672](https://github.com/clojure-emacs/cider-nrepl/pull/672): Fix debug instrumentation for a dot special form case.

### Changes

* Rename the `symbol` param in the `undef` middleware to `sym`. (the old name is retained for backwards compatibility)
* Rename the `symbol` param in the `complete` middleware to `prefix`. (the old name is retained for backwards compatibility)
* Rename the `symbol` param in all the `info` middleware ops to `sym`. (the old name is retained for backwards compatibility)

## 0.24.0 (2020-02-14)

### Bugs fixed

* [#663](https://github.com/clojure-emacs/cider-nrepl/pull/663): Fix non-recognized `recur` inside `case` in the debugger.
* [#664](https://github.com/clojure-emacs/cider-nrepl/pull/664): Fix continue-all in conditional break.
* [#665](https://github.com/clojure-emacs/cider-nrepl/pull/665): Fix form location in map literals.

### Changes

* [#641](https://github.com/clojure-emacs/cider-nrepl/pull/641): Integrate the new suitable ClojureScript completion.

## 0.23.0 (2019-01-18)

### New Features

* [#613](https://github.com/clojure-emacs/cider-nrepl/pull/613): Change the `:inspect` command to work on the current expression. Add a new command `:inspect-prompt` to inspect an arbitrary value.
* [#654](https://github.com/clojure-emacs/cider-nrepl/pull/654): Change the format of debugger command messages to a set of command names, leaving key mappings up to client implementations.
* [#653](https://github.com/clojure-emacs/cider-nrepl/pull/653): Add `inspect-def-current-value` to inspect middleware.

### Bugs fixed

* [#661](https://github.com/clojure-emacs/cider-nrepl/pull/661): Fix `info` op not to return nil as a value of key.

## 0.22.4 (2019-10-08)

### Changes

* Dynamic cljs completions (via suitable) are only enabled when adding `enhanced-cljs-completion` to cljs message like:

```clojure
{:op "complete"
 :ns "..."
 :prefix "..."
 :enhanced-cljs-completion? t}
```

### Bugs fixed

* [#652](https://github.com/clojure-emacs/cider-nrepl/pull/652): Respect `*print-length*` and `*print-level*` in the debugger.
* Respect nREPL print options in the debugger.
* Fix lockups and exceptions caused by clojuredocs cache download/corruption (fixed in Orchard 0.5.2).

## 0.22.3 (2019-09-11)

### Bugs fixed

* Bump the `orchard` dep to 0.5.1 to address missing specs for var metadata.
* Bump the `suitable` dep to address a NPE.

## 0.22.2 (2019-09-03)

### Bugs fixed

* [#643](https://github.com/clojure-emacs/cider-nrepl/issues/643): Fix ClojureScript completion when using a `node` REPL.

## 0.22.1 (2019-09-01)

### Bugs fixed

* [#642](https://github.com/clojure-emacs/cider-nrepl/pull/642): This fixes calls to suitable when the completion context contains no `__prefix__`.

## 0.22.0 (2019-08-29)

### New features

* [#605](https://github.com/clojure-emacs/cider-nrepl/pull/605): Added a option for filtering vars to the ns-vars middleware.
* Added `xref` middleware providing `fn-deps` and `fn-refs` ops.
* [#628](https://github.com/clojure-emacs/cider-nrepl/pull/628): Added `clojuredocs` middleware providing `clojuredocs-lookup` and `clojuredocs-refresh-cache` ops.
* [#633](https://github.com/clojure-emacs/cider-nrepl/pull/633) Added runtime code completion for ClojureScript via [suitable](https://github.com/rksm/clj-suitable).

### Bugs fixed

* Fix some functions defined with `def` not being properly font locked when using dynamic font locking for ClojureScript.
* [#618](https://github.com/clojure-emacs/cider-nrepl/pull/618): Fix apropos to honor exclude-regexps to filter out namespaces by regex
* [#605](https://github.com/clojure-emacs/cider-nrepl/pull/605): Fix `ns-vars-with-meta` to return public vars.

### Changes

* **(Breaking)** Removed the `cider.nrepl.main` namespace. The functionality in it has been superseded by `nrepl.cmdline`.
* [#615](https://github.com/clojure-emacs/cider-nrepl/pull/615): **(Breaking)** Removed the `cider.tasks/nrepl-server` custom task.

## 0.21.1 (2019-02-15)

### New features

* Add a wrapper function for `zprint`, suitable for using with `nrepl.middleware.print`.

## 0.21.0 (2019-02-11)

### New features

* The `refresh` op is now interruptible.

### Changes

* **(Breaking)** Upgrade to nREPL 0.6.0. This is now the minimum required version.
* **(Breaking)** Upgrade to piggieback 0.4.0. This is now the minimum required version.
* **(Breaking)** Remove `cider.nrepl.middleware.pprint`. All functionality has been replaced by the built-in printing support in nREPL 0.6.0.

## 0.20.0 (2019-01-14)

### New features

* Add print functions compatible with nREPL 0.5 to `cider.nrepl.pprint` namespace.

### Changes

* **(Breaking)** Drop support for nREPL 0.2.x (aka `tools.nrepl`). Now nREPL 0.4+ is required.
* **(Breaking)** Drop support for Piggieback 0.2.x (aka `cemerick.piggieback`).  Now Piggieback 0.3+ is required.
* Deprecated `cider.nrepl.middleware.pprint` if favour of the built-in pprint support in nREPL 0.5.

## 0.19.0 (2019-01-01)

### New features

* [#546](https://github.com/clojure-emacs/cider-nrepl/pull/546): Added support for matcher-combinators to the test middleware.
* [#556](https://github.com/clojure-emacs/cider-nrepl/pull/556): Added configuration option for cljfmt to the format middleware.
* [#558](https://github.com/clojure-emacs/cider-nrepl/pull/558): Added the `ns-aliases` op to the ns middleware.

### Changes

* [#550](https://github.com/clojure-emacs/cider-nrepl/pull/550): Always return test documentation messages as strings.
* [#563](https://github.com/clojure-emacs/cider-nrepl/pull/563): Add :root-ex key to error summary that contains the classname of the root cause.

### Bugs fixed

* [#573](https://github.com/clojure-emacs/cider-nrepl/pull/573): Fix inspector silently doing nothing if eval errored

## 0.18.0 (2018-08-06)

### New features

* [#540](https://github.com/clojure-emacs/cider-nrepl/pull/540): Added support for nREPL 0.4.
* [#532](https://github.com/clojure-emacs/cider-nrepl/pull/532): Added a boot task to start the nREPL server (allows us to run nREPL 0.4 before boot upgrades to it).

### Changes

* Drop "official" support for Java 7 and Clojure 1.7 (although they might still work for a while).
* Extract the `info` related functionality to `orchard`.

### Bugs fixed

* [#535](https://github.com/clojure-emacs/cider-nrepl/pull/535): Check for cemerick/piggieback, before checking for
cider/piggieback.
* [#542](https://github.com/clojure-emacs/cider-nrepl/issues/542): Qualify references to `*out*` and `*err*` in `wrap-out`.

## 0.17.0 (2018-05-07)

### New features

* Extracted part of the nREPL-agnostic functionality to `orchard`.
* Added a profiling middleware.
* Support for orchard var-query in apropos.
* Support for orchard var-query in test, introducing new test-var-query.

### Changes

* Remove support for cljx.
* Remove support for piggieback 0.1.x.
* Add support for piggieback 0.3 or newer (aka `cider/piggieback`).
* Deprecate the `test` and `test-all` ops.
* Deprecated non-test-var filters in the `apropos` middleware.
