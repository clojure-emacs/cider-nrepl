[![Build Status](https://travis-ci.org/clojure-emacs/cider-nrepl.png?branch=master)](https://travis-ci.org/clojure-emacs/cider-nrepl)

# CIDER nREPL

A collection of [nREPL](https://github.com/clojure/tools.nrepl) middleware designed to enhance
[CIDER](https://github.com/clojure-emacs/cider).

## Usage

### Via Leiningen

Use the convenient plugin for defaults, either in your project's
`project.clj` file or in the `:user` profile in
`~/.lein/profiles.clj`.

```clojure
:plugins [[cider/cider-nrepl "0.8.0"]]
```

A minimal `profiles.clj` for CIDER would be:

```clojure
{:user {:plugins [[cider/cider-nrepl "0.8.0"]]}}
```

Or (if you know what you're doing) add `cider-nrepl` to your `:dev :dependencies` vector plus specific
middleware to to `:nrepl-middleware` under `:repl-options`.

```clojure
:dependencies [[cider/cider-nrepl "0.8.0"]]
:repl-options {:nrepl-middleware
                 [cider.nrepl.middleware.apropos/wrap-apropos
                  cider.nrepl.middleware.classpath/wrap-classpath
                  cider.nrepl.middleware.complete/wrap-complete
                  cider.nrepl.middleware.info/wrap-info
                  cider.nrepl.middleware.inspect/wrap-inspect
                  cider.nrepl.middleware.macroexpand/wrap-macroexpand
                  cider.nrepl.middleware.ns/wrap-ns
                  cider.nrepl.middleware.resource/wrap-resource
                  cider.nrepl.middleware.stacktrace/wrap-stacktrace
                  cider.nrepl.middleware.test/wrap-test
                  cider.nrepl.middleware.trace/wrap-trace
                  cider.nrepl.middleware.undef/wrap-undef]}

```

Note that you should use a `cider-nrepl` version compatible with your CIDER. Generally, if you're
using CIDER 0.x.y you should be using `cider-nrepl` 0.x.y, if you're using CIDER 0.x.y-SNAPSHOT, you should be
using `cider-nrepl` 0.x.y-SNAPSHOT, etc.

### Via embedding nREPL in your app

If you're embedding nREPL in your application you'll have to start the
server with CIDER's own nREPL handler.

```clojure
(ns my-app
  (:require [clojure.tools.nrepl.server :as nrepl-server]
            [cider.nrepl :refer (cider-nrepl-handler)]))

(defn -main
  []
  (nrepl-server/start-server :port 7888 :handler cider-nrepl-handler))
```

### With Immutant
Using the advanced features of the `info` middleware with WildFly/Immutant
requires a tweek, since JBoss modules prevent modifications to `AppClassLoader`
(usually the highest modifiable classloader) from being seen by application
code. To work around this, run the following code from within your
WildFly/Immutant container to mark that classloader as unmodifiable, and cause
the lower level `clojure.lang.DynamicClassLoader` to be used instead. This code
must execute prior to loading the `cider-nrepl` middleware.

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
`wrap-info`       | `info/eldoc` | File/line, arglists, docstrings and other metadata for vars.
`wrap-inspect`    |`inspect-(start/refresh/pop/push/reset)` | Inspect a Clojure expression.
`wrap-macroexpand`| `macroexpand/macroexpand-1/macroexpand-all` | Macroexpand a Clojure form.
`wrap-ns`         | `ns-list/ns-vars` | Namespace browsing.
`wrap-resource`   | `resource` | Return resource path.
`wrap-stacktrace` | `stacktrace` | Cause and stacktrace analysis for exceptions.
`wrap-test`       | `test/retest/test-stacktrace` | Test execution, reporting, and inspection.
`wrap-trace`      | `toggle-trace-var`/`toggle-trace-ns` | Toggle tracing of a given var or ns.
`wrap-undef`      | `undef`    | Undefine a var.

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

The versions of CIDER and cider-nrepl are always kept in sync. If you're tracking the
`master` branch of CIDER, you should also be tracking the `master` branch of `cider-nrepl`.

## Contributing

### Discussion

For questions, suggestions and support refer to our [official mailing list](https://groups.google.com/forum/#!forum/cider-emacs)
or the Freenode channel `#clojure-emacs`.
Please, don't report issues there, as this makes them harder to track.

### Issues

Report issues and suggest features and improvements on the
[GitHub issue tracker](https://github.com/clojure-emacs/cider-nrepl/issues). Don't ask
questions on the issue tracker - the mailing list and the IRC channel are the
places for questions.

### Patches

Patches under any form are always welcome! GitHub pull requests are even better! :-)

Before submitting a patch or a pull request make sure all tests are
passing and that your patch is in line with the [contribution
guidelines](CONTRIBUTING.md).

## Hall of Fame

Special credit goes to the following people for their contributions:

- Gary Trakhman ([@gtrak](https://github.com/gtrak))
- Jeff Valk ([@jeffvalk](https://github.com/jeffvalk))
- Hugo Duncan ([@hugoduncan](https://github.com/hugoduncan))

And the a big thanks to
[all other contributors](https://github.com/clojure-emacs/cider-nrepl/graphs/contributors)
who have helped so far.

Let's also acknowledge some of the projects leveraged by cider-nrepl:

* [compliment](https://github.com/alexander-yakushev/compliment) - for Clojure code completion
* [cljs-tooling](https://github.com/gtrak/cljs-tooling) - for ClojureScript code completion and var info
* [tools.trace](https://github.com/clojure/tools.trace) - for tracing
* [tools.namespace](https://github.com/clojure/tools.namespace) - for namespace reloading

## License

Copyright © 2013-2014 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.
