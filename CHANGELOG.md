# Changelog

## master (unreleased)

## 0.57.0 (2025-06-29)

* Bump `orchard` to [0.36.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0360-2025-06-29).

## 0.56.0 (2025-05-29)

* Bump `orchard` to [0.35.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0350-2025-05-28).
* [#941](https://github.com/clojure-emacs/cider-nrepl/pull/941): Stop vendoring Fipp dependency.
* [#941](https://github.com/clojure-emacs/cider-nrepl/pull/941): Default to orchard.pp printer when Fipp/Puget/Zprint is selected but not found on classpath.
* [#943](https://github.com/clojure-emacs/cider-nrepl/pull/943): Debug: reduce insrumentation bytecode footprint.

## 0.55.7 (2025-04-29)

* [#939](https://github.com/clojure-emacs/cider-nrepl/pull/939): Tracker: synchronize access to WeakHashMap cache to prevent infinite loops.

## 0.55.6 (2025-04-28)

* Bump `orchard` to [0.34.3](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0343-2025-04-28).

## 0.55.4 (2025-04-26)

* Bump `orchard` to [0.34.2](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0342-2025-04-26).

## 0.55.3 (2025-04-24)

* [#933](https://github.com/clojure-emacs/cider-nrepl/pull/933): Add the `cider-inspector-print-current-value` command to print the current value of the inspector.
* Bump `orchard` to [0.34.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0341-2025-04-23).
* [#935](https://github.com/clojure-emacs/cider-nrepl/pull/935): Unify injected print-method implementations with orchard.print.

## 0.55.2 (2025-04-18)

* Bump `orchard` to [0.34.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0340-2025-04-18).
* [#934](https://github.com/clojure-emacs/cider-nrepl/pull/934): **BREAKING** — Remove Boot support.
* [#932](https://github.com/clojure-emacs/cider-nrepl/pull/932): Inspector: add pretty-printing support.

## 0.55.1 (2025-04-14)

* [#931](https://github.com/clojure-emacs/cider-nrepl/pull/931): Redesign and optimize track-state middleware.

## 0.55.0 (2025-04-10)

* Bump `orchard` to [0.33.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0330-2025-04-08).
* [#929](https://github.com/clojure-emacs/cider-nrepl/pull/929): Migrate profiling middleware to orchard.profile.
* [#930](https://github.com/clojure-emacs/cider-nrepl/pull/930): Stacktrace: add support for directly inspecting ex-data

## 0.54.0 (2025-04-05)

* Bump `orchard` to [0.32.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0320-2025-04-05).
* [#925](https://github.com/clojure-emacs/cider-nrepl/pull/925): Stop vendoring Puget dependency.
* [#917](https://github.com/clojure-emacs/cider-nrepl/pull/917): Sort printed maps in test output.
* [#927](https://github.com/clojure-emacs/cider-nrepl/pull/927): Add `inspect-display-analytics` op.
* [#928](https://github.com/clojure-emacs/cider-nrepl/pull/922): Add support for `:table` view-mode in inspector.

## 0.53.2 (2025-03-26)

* [#923](https://github.com/clojure-emacs/cider-nrepl/pull/923): Complete: make sorting order customizable.

## 0.53.1 (2025-03-26)

* Bump `compliment` to [0.7.0](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#070-2025-03-25).

## 0.53.0 (2025-03-19)

* Bump `orchard` to [0.31.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0311-2025-03-19).
  - Info: recognize printed Java classes/methods and munged Clojure functions in stacktrace outputs.
  - Add dedicated renderers for exceptions in inspector and debugger.
* [#919](https://github.com/clojure-emacs/cider-nrepl/pull/919): Move exception analysis to Orchard.
  - **BREAKING**: Remove `analyze-stacktrace` and `cider/log-analyze-stacktrace` ops.
  - Stop vendoring Haystack dependency.
* [#913](https://github.com/clojure-emacs/cider-nrepl/pull/913): Disable background warmup of `orchard.java` cache.
* [#913](https://github.com/clojure-emacs/cider-nrepl/pull/913): Enable background warmup of Compliment cache.
* [#914](https://github.com/clojure-emacs/cider-nrepl/pull/914): Remove javadoc section from the inspector output.

## 0.52.1 (2025-02-24)

* Bump `orchard` to [0.30.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0301-2025-02-24).

## 0.52.0 (2025-01-10)

* Bump `orchard` to [0.30.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0300-2025-01-10).
* * [#911](https://github.com/clojure-emacs/cider-nrepl/pull/911): Add support for automatically downloading sources JARs in `info` op.

## 0.51.1 (2025-01-03)

* Bump `orchard` to [0.29.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0291-2025-01-03).
* [#910](https://github.com/clojure-emacs/cider-nrepl/pull/910): Protect Orchard Java machinery initialization with a try/catch.

## 0.51.0 (2025-01-01)

* Bump `orchard` to [0.29.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0290-2024-12-31).
  - **BREAKING**: Drop support for Java sources parsing on JDK8.
  - Rework Java sources discovery.

## 0.50.3 (2024-12-02)

* Bump `orchard` to [0.28.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0280-2024-10-31).
* [#885 (partial)](https://github.com/clojure-emacs/cider-nrepl/issues/885): Limited update to `info` op doc (`arglists-str`, `file`, `line`, `column`, `name` and `ns` return keys).

## 0.50.2 (2024-09-03)

* [#900](https://github.com/clojure-emacs/cider-nrepl/pull/900): Fix print middleware interfering with macroexpansion.

## 0.50.1 (2024-08-28)

* Bump `orchard` to [0.27.2](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0272-2024-08-28).

## 0.50.0 (2024-08-28)

* [#893](https://github.com/clojure-emacs/cider-nrepl/pull/893): Replace `clojure.tools.trace` with `orchard.trace`.
* [#894](https://github.com/clojure-emacs/cider-nrepl/pull/894): Delegate actual macroexpansion to "eval" command in middleware.macroexpand.
* Bump `orchard` to [0.27.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0271-2024-08-27).
  - BREAKING: Remove special handling of Boot classpath.

## 0.49.3 (2024-08-13)

### Changes

* Bump `compliment` to [0.6.0](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#060-2024-08-13).

## 0.49.2 (2024-07-19)

### Changes

* Bump `orchard` to [0.26.2](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0262-2024-07-19)

## 0.49.1 (2024-06-30)

### Changes

* [#883](https://github.com/clojure-emacs/cider-nrepl/pull/883): Reduce minimal Clojure requirement to 1.10.0.

## Bugs fixed

* [CIDER#3684](https://github.com/clojure-emacs/cider/issues/3684): Don't apply indentation inference for `:style/indent nil` metadata.

## 0.49.0 (2024-06-02)

### Changes

* [#877](https://github.com/clojure-emacs/cider-nrepl/pull/877): `inspect-refresh` middleware is now capable of setting all config options that `orchard.inspect` supports.
* [#877](https://github.com/clojure-emacs/cider-nrepl/pull/877): Deprecate all `inspect-set-*` middleware ops.
* [#879](https://github.com/clojure-emacs/cider-nrepl/pull/879), [#880](https://github.com/clojure-emacs/cider-nrepl/pull/880): Add `inspect-toggle-view-mode` op.
* Bump `orchard` to [0.26.1](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0261-2024-06-02)

## 0.48.0 (2024-05-13)

### Changes

* [#868](https://github.com/clojure-emacs/cider-nrepl/pull/868): Drop support for Clojure 1.9.
* Refine `ops-that-can-eval` internals, adapting them to the new `cider.nrepl.middleware.reload` ops.
* Bump `nrepl` to [1.1.1](https://github.com/nrepl/nrepl/blob/master/CHANGELOG.md#111-2024-02-20).
* Bump `compliment` to [0.5.5](https://github.com/alexander-yakushev/compliment/blob/master/CHANGELOG.md#055-2024-05-06).
* Bump `clj-reload` to [0.6.0](https://github.com/tonsky/clj-reload/blob/0.6.0/CHANGELOG.md#060---may-3-2024).
* Bump `tools.trace` to 0.8.0 (no changes in source code).
* Bump `tools.reader` to [1.4.1](https://github.com/clojure/tools.reader/blob/master/CHANGELOG.md).
* Bump `orchard` to [0.25.0](https://github.com/clojure-emacs/orchard/blob/master/CHANGELOG.md#0250-2024-05-03).
  * Bring many improvements to CIDER Inspector (see Orchard CHANGELOG).
* Add `inspect-set-max-nested-depth` op that customizes how many nested levels the Inspector will print before abridging.
* [#826](https://github.com/clojure-emacs/cider-nrepl/pull/826): Remove broken `inspect-get-path` middleware op, return path in every inspector middleware op instead.
* Reduce the print length of values that CIDER debugger produces for overlay.
* [cider#3652](https://github.com/clojure-emacs/cider/issues/3652) `middleware.refresh`: elide a lock for the `"refresh-clear"` op.

## 0.47.1 (2024-03-24)

### Changes

* Bump `orchard` to [0.23.3](https://github.com/clojure-emacs/orchard/blob/v0.23.3/CHANGELOG.md#0233-2024-03-24).

## 0.47.0 (2024-03-10)

### Changes

* Bump `orchard` to [0.23.2](https://github.com/clojure-emacs/orchard/blob/v0.23.2/CHANGELOG.md#0232-2024-03-10).
* Bump `compliment` to [0.5.2](https://github.com/alexander-yakushev/compliment/blob/14329344/CHANGELOG.md#052-2024-03-07).

## Bugs fixed

* [#854](https://github.com/clojure-emacs/cider-nrepl/pull/854): Fix `cider.nrepl.middleware.reload/reload-all`.
* `cider.nrepl.middleware.reload`: support `:before` / `:after` functions.

## 0.46.0 (2024-03-05)

### New features

* [#850](https://github.com/clojure-emacs/cider-nrepl/pull/850): Add [clj-reload](https://github.com/tonsky/clj-reload) functionality by implementing new `cider.clj-reload/reload`, `cider.clj-reload/reload-all`, `cider.clj-reload/reload-clear` ops.
  * These are drop-in replacements for `refresh`, `refresh-all`, `refresh-clear`.

### Changes

* Bump `orchard` to [0.23.0](https://github.com/clojure-emacs/orchard/blob/v0.23.0/CHANGELOG.md#0230-2024-03-03).
* Bump `logjam` to [0.3.0](https://github.com/clojure-emacs/logjam/blob/v0.3.0/CHANGELOG.md#030-2024-03-03).
* [#851](https://github.com/clojure-emacs/cider-nrepl/issues/851): `middleware.info`: offer new `var-meta-allowlist` option.

## Bugs fixed

*  [#846](https://github.com/clojure-emacs/cider-nrepl/issues/846): `middleware.debug`: use `resolve` with `&env` to fix debugging in case of a local shadows a var.

## 0.45.0 (2024-01-14)

### Changes

* Bump `orchard` to [0.22.0](https://github.com/clojure-emacs/orchard/blob/v0.22.0/CHANGELOG.md#0220-2024-01-14).
* Bump `logjam` to [0.2.0](https://github.com/clojure-emacs/logjam/blob/v0.2.0/CHANGELOG.md#020-2024-01-04).
* Bump `suitable` to [0.6.2](https://github.com/clojure-emacs/clj-suitable/blob/v0.6.2/CHANGELOG.md#062-2033-01-14).
* Bump `compliment` to [0.5.1](https://github.com/alexander-yakushev/compliment/blob/6119d8/CHANGELOG.md#051-2023-11-30).
* [#842](https://github.com/clojure-emacs/cider-nrepl/issues/842): Initial cache warmup: ensure that no classes' static initializers are run.
* [#840](https://github.com/clojure-emacs/cider-nrepl/pull/840): Drop support for Clojure 1.8.
* Forcibly exit the JVM on older (unsupported) Clojure versions.
  * This can help users and maintainers alike diagnose issues more quickly, avoiding problematic code paths in our middleware, and clients like cider.el.

## 0.44.0 (2023-11-24)

### New features

* Add `inspect-tap-indexed` op.

### Changes

* `middleware.inspect:` warm up Orchard info for a value's class implemented interfaces in advance.
  * Can speed up navigation.

## 0.43.3 (2023-11-11)

### Changes

* Bump `orchard` to [0.20.0](https://github.com/clojure-emacs/orchard/blob/v0.20.0/CHANGELOG.md#0200-2023-11-11).
* Bump `haystack` to [0.3.3](https://github.com/clojure-emacs/haystack/blob/v0.3.3/CHANGELOG.md#033-2023-11-11).
  * Fixes a performance regression.

## 0.43.1 (2023-11-07)

### Changes

* [#828](https://github.com/clojure-emacs/cider-nrepl/issues/828): Warmup Orchard caches for compile-time exceptions in advance.
  * This is similar to a change introduced in 0.43.0, but for compile-time exceptions this time.
* Bump `suitable` to [0.6.1](https://github.com/clojure-emacs/clj-suitable/blob/62785/CHANGELOG.md#061-2023-11-07).

## Bugs fixed

* Make `"inspect-refresh"` op work again.

## 0.43.0 (2023-11-04)

### New features

* [cider#3565](https://github.com/clojure-emacs/cider/issues/3565): Add new [`inspect-last-exception`](https://docs.cider.mx/cider-nrepl/nrepl-api/ops.html#inspect-last-exception) op.
* Inspector: include [`:doc-fragments`](https://docs.cider.mx/cider-nrepl/nrepl-api/ops.html#inspect-push) for all responses which `:value` is a Class, Field or Method.

### Changes

* [#830](https://github.com/clojure-emacs/cider-nrepl/issues/830): Warmup Orchard caches for Java imported classes in advance.
  * Speculatively improves performance for the classes that users are more likely to use in a given project.
* [#828](https://github.com/clojure-emacs/cider-nrepl/issues/828): Warmup Orchard caches for exceptions in advance.
  * This noticeably improves the first-time performance of exception-related ops, e.g. `analyze-last-stacktrace`.
* Bump `orchard` to [0.19.0](https://github.com/clojure-emacs/orchard/blob/v0.19.0/CHANGELOG.md#0190-2023-11-04).

## 0.42.1 (2023-10-31)

* Bump `suitable` to [0.5.1](https://github.com/clojure-emacs/clj-suitable/blob/v0.5.1/CHANGELOG.md#051-2023-10-31).

## 0.42.0 (2023-10-30)

### Changes

* Bump `orchard` to [0.18.0](https://github.com/clojure-emacs/orchard/blob/v0.18.0/CHANGELOG.md#0180-2023-10-30).
* Warmup Orchard (`info`, `complete`, `eldoc`) caches in advance.
  * Restores the first-time performance that had been lost for a few releases.

## 0.41.0 (2023-10-24)

### New features

* `middleware.inspect:` add new `inspect-tap-current-value` op.
* `middleware.inspect:` add new `inspect-previous-sibling`, `inspect-next-sibling` ops.

## 0.40.0 (2023-10-15)

### Changes

* `track-state`: include var info for macros defined for ClojureScript namespaces.
  * e.g. for `foo.cljs`, now var info for any macros contained in `foo.clj` is also included.

## 0.39.1 (2023-10-12)

### Changes

* Bump `compliment` to [0.4.4](https://github.com/alexander-yakushev/compliment/blob/0.4.4/CHANGELOG.md#044-2023-10-10).

## 0.39.0 (2023-10-05)

### Changes

* Bump `orchard` to [0.16.1](https://github.com/clojure-emacs/orchard/blob/v0.16.1/CHANGELOG.md#0161-2023-10-05).
* Bump `haystack` to [0.3.1](https://github.com/clojure-emacs/haystack/blob/v0.3.1/CHANGELOG.md#031-2023-09-29).

### Bugs Fixed

* `info` and `eldoc` ops: fix regression for the special form `..`.

## 0.38.1 (2023-09-21)

* Bump `orchard` to [0.15.1](https://github.com/clojure-emacs/orchard/blob/v0.15.1/CHANGELOG.md#0151-2023-09-21).

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
