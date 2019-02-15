# Changelog

## master (unreleased)

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
