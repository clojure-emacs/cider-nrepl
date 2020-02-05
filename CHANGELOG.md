# Changelog

## master (unreleased)

### New Features

### Changes

* [#663](https://github.com/clojure-emacs/cider-nrepl/pull/663): Fix non-recognized `recur` inside `case` in the debugger.
* [#664](https://github.com/clojure-emacs/cider-nrepl/pull/664): Fix continue-all in conditional break.
* [#665](https://github.com/clojure-emacs/cider-nrepl/pull/665): Fix form location in map literals.

## 0.23.0 (2019-01-18)

### New Features

* [#613](https://github.com/clojure-emacs/cider-nrepl/pull/613): Change the `:inspect` command to work on the current expression. Add a new command `:inspect-prompt` to inspect an arbitrary value.
* [#654](https://github.com/clojure-emacs/cider-nrepl/pull/654): Change the format of debugger command messages to a set of command names, leaving key mappings up to client implementations.
* [#653](https://github.com/clojure-emacs/cider-nrepl/pull/653): Add `inspect-def-current-value` to inspect middleware.

### Changes

* [#661](https://github.com/clojure-emacs/cider-nrepl/pull/661): Fix `info` op not to return nil as a value of key.

## 0.22.4 (2019-10-08)

### Changes

* Dynamic cljs completions (via suitable) are only enabled when adding `enhanced-cljs-completion` to cljs message like:

```clojure
{:op "complete"
 :ns "..."
 :symbol "..."
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
