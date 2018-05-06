[![Build Status](https://travis-ci.org/clojure-emacs/cider-nrepl.png?branch=master)](https://travis-ci.org/clojure-emacs/cider-nrepl)
[![Dependencies Status](https://versions.deps.co/clojure-emacs/cider-nrepl/status.svg)](https://versions.deps.co/clojure-emacs/cider-nrepl)
[![Coverage](https://codecov.io/gh/clojure-emacs/cider-nrepl/branch/master/graph/badge.svg)](https://codecov.io/gh/clojure-emacs/cider-nrepl/)

# CIDER nREPL

A collection of [nREPL](https://github.com/cemerick/nREPL)
middleware designed to enhance
[CIDER](https://github.com/clojure-emacs/cider).

If you're just a user trying to get started with CIDER, then you
probably don't want to read this. You should follow the steps in
[CIDER's manual](https://cider.readthedocs.io) instead.

If you're trying to use cider-nrepl for some other purpose, the
sections below outline how to include it in a project.

People who are new to nREPL might benefit from reading [this nice
overview](https://juxt.pro/blog/posts/nrepl.html) of its capabilities.

## Design

This section documents some of the major design decisions in cider-nrepl.

While in essence it's just a collection of nREPL middleware we had to
make a few important design decision here and there that influenced
the code base and the usability of cider-nrepl in various ways.

### Editor Agnostic

Although those middlewares were created for use with CIDER almost all
of them are extremely generic and can be leveraged from other editors.

Projects like [vim-fireplace][] and [vim-replant][] are making use of
cider-nrepl already.

### Isolated Runtime Dependencies

All of cider-nrepl's dependencies are processed with
[mranderson][], so that
they won't collide with the dependencies of your own projects. This
basically means that cider-nrepl doesn't have any runtime dependencies in
the production artifact - just copies of the deps inlined with changed
namespaces/packages. It's a bit ugly and painful, but it gets the job
done.

If someone has better ideas how to isolate our runtime dependencies -
we're all ears!

### Deferred Middleware Loading

To improve the startup time of the nREPL server all of cider-nrepl's
middleware's are loaded for real only when needed.

You can read more about this
[here](https://github.com/clojure-emacs/cider-nrepl/pull/438).

We'd love to bring the support for deferred middleware loading
straight to nREPL down the road.

### Middleware Errors Never Hang Requests

See [here](https://github.com/clojure-emacs/cider-nrepl/pull/327).

### Reusable Core Logic

cider-nrepl tries to have as little logic as possible and mostly
provides thin wrappers over existing libraries (e.g. [compliment][],
[cljfmt][], etc). Much of its core functionality lives in
[orchard](https://github.com/clojure-emacs/orchard), so that
eventually it can be used by non-nREPL clients (e.g. Socket REPL
clients).

Very simply put - there's very little code in cider-nrepl that's not
simply wrapping code from other libraries in nREPL operations.

The primary reason for this is our desire to eventually provide
support for non-nREPL REPLs in CIDER, but this also means that other
editors can directly leverage the work we've done so far.

### ClojureScript Support

We want cider-nrepl to offer feature parity between Clojure and
ClojureScript, but we're not quite there yet and many features right
now are Clojure-only.

We'd really appreciate all the help we can get from ClojureScript
hackers to make this a reality.

## Usage

### Prerequisites

`cider-nrepl` supports only Clojure(Script) 1.7+ and Java 7+.

Leiningen users will need to have version 2.5.2 or newer installed.

### Via Leiningen

Use the convenient plugin for defaults, either in your project's
`project.clj` file or in the `:user` profile in
`~/.lein/profiles.clj`.

```clojure
:plugins [[cider/cider-nrepl "0.16.0"]]
```

A minimal `profiles.clj` for CIDER would be:

```clojure
{:user {:plugins [[cider/cider-nrepl "0.16.0"]]}}
```

Or (if you know what you're doing) add `cider-nrepl` to your `:dev
:dependencies` vector plus specific middleware to `:nrepl-middleware`
under `:repl-options`.

```clojure
:dependencies [[cider/cider-nrepl "0.16.0"]]
:repl-options {:nrepl-middleware
                 [cider.nrepl/wrap-apropos
                  cider.nrepl/wrap-classpath
                  cider.nrepl/wrap-complete
                  cider.nrepl/wrap-debug
                  cider.nrepl/wrap-format
                  cider.nrepl/wrap-info
                  cider.nrepl/wrap-inspect
                  cider.nrepl/wrap-macroexpand
                  cider.nrepl/wrap-ns
                  cider.nrepl/wrap-spec
                  cider.nrepl/wrap-pprint
                  cider.nrepl/wrap-pprint-fn
                  cider.nrepl/wrap-profile
                  cider.nrepl/wrap-refresh
                  cider.nrepl/wrap-resource
                  cider.nrepl/wrap-stacktrace
                  cider.nrepl/wrap-test
                  cider.nrepl/wrap-trace
                  cider.nrepl/wrap-out
                  cider.nrepl/wrap-undef
                  cider.nrepl/wrap-version]}

```

Note that you should use a `cider-nrepl` version compatible with your
CIDER. Generally, if you're using CIDER 0.x.y you should be using
`cider-nrepl` 0.x.y, if you're using CIDER 0.x.y-SNAPSHOT, you should
be using `cider-nrepl` 0.x.y-SNAPSHOT, etc.

#### Via Boot

Boot users can configure the injected middleware by either specifying
it on the command line through the `cider.tasks/add-middleware` task
(the lenghty command below will include the `apropos` and `version`
functionality):

```
boot -d org.clojure/tools.nrepl:0.2.12 -d cider/cider-nrepl:0.x.y-SNAPSHOT -i "(require 'cider.tasks)" cider.tasks/add-middleware -m cider.nrepl.middleware.apropos/wrap-apropos -m cider.nrepl.middleware.version/wrap-version repl -s wait
```

Or for all of their projects by adding a `~/.boot/profile.boot` file like so:

```clojure
(set-env! :dependencies '[[org.clojure/tools.nrepl "0.2.12"]
                          [cider/cider-nrepl "0.x.y-SNAPSHOT"]])

(require '[cider.tasks :refer [add-middleware]])

(task-options! add-middleware {:middleware '[cider.nrepl.middleware.apropos/wrap-apropos
                                             cider.nrepl.middleware.version/wrap-version]})
```

And then launching `boot add-middleware repl -s wait`. Note that this
is not necessary when using the standard `cider-jack-in`.

For more information visit [boot-clj wiki](https://github.com/boot-clj/boot/wiki/Cider-REPL).

### Via clj

You can easily boot an nREPL server with the CIDER middleware loaded
with the following "magic" incantation:

```
clj -Sdeps '{:deps {cider/cider-nrepl {:mvn/version "0.17.0-SNAPSHOT"} }}' -e '(require (quote cider-nrepl.main)) (cider-nrepl.main/init ["cider.nrepl/cider-middleware"])'
```

Note that `clj` was introduced in Clojure 1.9.

### Via embedding nREPL in your app

If you're embedding nREPL in your application you'll have to start the
server with CIDER's own nREPL handler.

```clojure
(ns my-app
  (:require [clojure.tools.nrepl.server :as nrepl-server]))

(defn nrepl-handler []
  (require 'cider.nrepl)
  (ns-resolve 'cider.nrepl 'cider-nrepl-handler))

(defn -main []
  (nrepl-server/start-server :port 7888 :handler (nrepl-handler)))
```

(See [issue #447](https://github.com/clojure-emacs/cider-nrepl/issues/447) for why the manual namespace resolution of `cider-nrepl-handler` is currently necessary.)

### With JBoss AS/JBoss EAP/WildFly

Using the advanced features of the `info` middleware with one of the
JBoss application servers requires a tweak, since JBoss modules
prevent modifications to `AppClassLoader` (usually the highest
modifiable classloader) from being seen by application code. To work
around this, run the following code from within your application
to mark that classloader as unmodifiable, and cause the lower level
`clojure.lang.DynamicClassLoader` to be used instead. This code must
execute prior to loading the `cider-nrepl` middleware. Note that this
is only if you are deploying a standard WAR file to the application
server directly. If you are using [Immutant](http://immutant.org/)
(1.x or 2.x), you won't need to do this.

```clj
(require '[dynapath.dynamic-classpath :as cp])

(extend sun.misc.Launcher$AppClassLoader
  cp/DynamicClasspath
  (assoc cp/base-readable-addable-classpath
    :classpath-urls #(seq (.getURLs %))
    :can-add? (constantly false)))
```


## Supplied nREPL middleware

Middleware        | Op(s)      | Description
------------------|------------|---------------------------------------------------------
`wrap-apropos`    | `apropos`  | Pattern search for symbols and documentation.
`wrap-classpath`  | `classpath` | Java classpath.
`wrap-complete`   | `complete` | Simple completion. Supports both Clojure & ClojureScript.
`wrap-debug`      | `init-debugger/debug-input` | Establish a channel for `cider-debug` commands, use it to get debug input, and also wrap the eval op.
`wrap-format`     | `format-(code/edn)` | Code and data formatting.
`wrap-info`       | `info/eldoc` | File/line, arglists, docstrings and other metadata for vars.
`wrap-inspect`    |`inspect-(start/refresh/pop/push/reset/get-path)` | Inspect a Clojure expression.
`wrap-macroexpand`| `macroexpand/macroexpand-1/macroexpand-all/macroexpand-step` | Macroexpand a Clojure form.
`wrap-ns`         | `ns-list/ns-vars/ns-path/ns-load-all` | Namespace browsing & loading.
`wrap-spec`         | `spec-list/spec-form/spec-example` | Spec browsing.
`wrap-pprint`     | | Adds pretty-printing support to code evaluation. It also installs a dummy `pprint-middleware` op. Thus `wrap-pprint` is discoverable through the `describe` op.
`wrap-pprint-fn`  | | Provides a common pretty-printing interface for other middlewares that need to perform customisable pretty-printing.
`wrap-profile`    | `toggle-profile/toggle-profile-ns/is-var-profiled/profile-summary/profile-var-summary/clear-profile/get-max-samples/set-max-samples` | Provides Profiling support for [Profiling](https://github.com/thunknyc/profile)
`wrap-refresh`    | `refresh/refresh-all/refresh-clear` | Code reloading.
`wrap-resource`   | `resource` | Return resource path.
`wrap-stacktrace` | `stacktrace` | Cause and stacktrace analysis for exceptions.
`wrap-test`       | `test-var-query/retest/test-stacktrace` | Test execution, reporting, and inspection.
`wrap-trace`      | `toggle-trace-var`/`toggle-trace-ns` | Toggle tracing of a given var or ns.
`wrap-out`        | | Echo the server's output stream to client sessions.
`wrap-undef`      | `undef`    | Undefine a var.
`wrap-version`    | `cider-version` | The CIDER-nREPL version map.

## Release policy

We’re following [SemVer](http://semver.org/) (as much as one can be
following it when the major version is 0). At this point bumps of the
minor (second) version number are considered major releases and always
include new features or significant changes to existing features. API
compatibility between major releases is not a (big) concern (although we try
to break the API rarely and only for a good reason).

The development cycle for the next major
release starts immediately after the previous one has been
shipped. Bugfix/point releases (if any) address only serious bugs and
never contain new features.

The versions of CIDER and cider-nrepl are always kept in sync. If
you're tracking the `master` branch of CIDER, you should also be
tracking the `master` branch of `cider-nrepl`.

## Contributing

### Discussion

For questions, suggestions and support refer to our [official mailing list](https://groups.google.com/forum/#!forum/cider-emacs)
or the Freenode channel `#clojure-emacs`.
Please, don't report issues there, as this makes them harder to track.

### Issues

Report issues and suggest features and improvements on the
[GitHub issue tracker](https://github.com/clojure-emacs/cider-nrepl/issues). Don't
ask questions on the issue tracker - the mailing list and the IRC
channel are the places for questions.

### Patches

Patches under any form are always welcome! GitHub pull requests are even better! :-)

Before submitting a patch or a pull request make sure all tests are
passing and that your patch is in line with the [contribution
guidelines](.github/CONTRIBUTING.md).

### Working with mranderson (inlining runtime dependencies)

[mranderson][] is used to
avoid classpath collisions.

To work with `mranderson` the first thing to do is:

```
lein do clean, source-deps :project-prefix cider.inlined-deps
```

This creates the munged local dependencies in `target/srcdeps` directory.

After that you can run your tests or your REPL with:

```
lein with-profile +plugin.mranderson/config repl
lein with-profile +plugin.mranderson/config test
```

Note the `+` sign before the leiningen profile. For this leiningen
profile to work **you need leiningen version 2.5.0+!** If you want to
use `mranderson` while developing locally with the REPL the source has
to be modified in the `target/srcdeps` directory. When you want to
release locally:

```
lein with-profile plugin.mranderson/config install
```

Release to [clojars](https://clojars.org/):

```
lein with-profile plugin.mranderson/config deploy clojars
```

Or you can use the "very sophisticated" `build.sh` script to clean,
inline, and run a lein target like `install` or `deploy`:

```
./build.sh install
./build.sh deploy clojars
```

[build.sh](build.sh) cleans, runs source-deps with the right
parameters, and then runs the provided lein target.

## Hall of Fame

Special credit goes to the following people for their contributions:

- Gary Trakhman ([@gtrak](https://github.com/gtrak))
- Jeff Valk ([@jeffvalk](https://github.com/jeffvalk))
- Hugo Duncan ([@hugoduncan](https://github.com/hugoduncan))
- Michael Griffiths ([@cichli](https://github.com/cichli))
- Artur Malabarba ([@malabarba](https://github.com/malabarba))
- Lars Andersen ([@expez](https://github.com/expez))
- Benedek Fazekas ([@benedekfazekas](https://github.com/benedekfazekas))

And a big thanks to
[all other contributors](https://github.com/clojure-emacs/cider-nrepl/graphs/contributors)
who have helped so far.

Let's also acknowledge some of the projects leveraged by cider-nrepl:

* [orchard][] - extracted from `cider-nrepl`, so that non-nREPL clients can leverage the generic tooling functionality (like `inspect`, `apropos`, `var-info`, etc
* [compliment][] - for Clojure code completion
* [cljs-tooling][] - for ClojureScript code completion and var info
* [tools.trace][] - for tracing
* [tools.namespace][] - for namespace reloading
* [cljfmt][] - for code formatting

## License

Copyright © 2013-2018 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.

[orchard]: https://github.com/clojure-emacs/orchard
[compliment]: https://github.com/alexander-yakushev/compliment
[cljs-tooling]: https://github.com/clojure-emacs/cljs-tooling
[tools.trace]: https://github.com/clojure/tools.trace
[tools.namespace]: (https://github.com/clojure/tools.namespace)
[cljfmt]: https://github.com/weavejester/cljfmt
[vim-replant]: https://github.com/SevereOverfl0w/vim-replant
[vim-fireplace]: https://github.com/tpope/vim-fireplace
