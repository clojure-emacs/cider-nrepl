# Changelog

## master (unreleased)

## 0.17.0 (2018-05-07)

### New features

* Extracted part of the nREPL-agnostic functionality to `orchard`.
* Added a profiling middleware.
* Support for orchard var-query in apropos
* Support for orchard var-query in test, introducing new test-var-query

### Changes

* Remove support for cljx.
* Remove support for piggieback 0.1.x.
* Require piggieback 0.3 or newer.
* Deprecate test and test-all
* Deprecated non-test-var filters in apropos
