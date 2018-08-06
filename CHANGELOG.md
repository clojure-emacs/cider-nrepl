# Changelog

## master (unreleased)

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
