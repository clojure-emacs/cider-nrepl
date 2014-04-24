[![Build Status](https://travis-ci.org/clojure-emacs/cider-nrepl.png?branch=master)](https://travis-ci.org/clojure-emacs/cider-nrepl)

# CIDER nREPL

A collection of nREPL middleware designed to enhance CIDER.

## Usage

Use the convenient plugin for defaults, either in your project's
`project.clj` file or in the `:user` profile in
`~/.lein/profiles.clj`.

```clojure
:plugins [[cider/cider-nrepl "0.6.1-SNAPSHOT"]]
```

Or add `cider-nrepl` to your `:dev :dependencies` vector plus specific
middleware to to `:nrepl-middleware` under `:repl-options`.

```clojure
:dependencies [[cider/cider-nrepl "0.6.1-SNAPSHOT"]]
:repl-options {:nrepl-middleware
                 [cider.nrepl.middleware.classpath/wrap-classpath
                  cider.nrepl.middleware.complete/wrap-complete
                  cider.nrepl.middleware.info/wrap-info
                  cider.nrepl.middleware.inspect/wrap-inspect
                  cider.nrepl.middleware.stacktrace/wrap-stacktrace]}
```

Note that you should use a `cider-nrepl` version compatible with your CIDER. Generally, if you're
using CIDER 0.6 you should be using `cider-nrepl` 0.6, if you're using CIDER 0.7-snapshot, you should be
using `cider-nrepl` 0.7-snapshot, etc.


## Supplied nREPL middleware

Middleware        | Op(s)      | Description
------------------|------------|---------------------------------------------------------
`wrap-classpath`  | `classpath` | Java classpath.
`wrap-complete`   | `complete` | Simple completion. Supports both Clojure & ClojureScript.
`wrap-info`       | `info`     | File/line, arglists, docstrings and other metadata for vars.
`wrap-inspect`    |`inspect-(start/refresh/pop/push/reset)` | Inspect a Clojure expression.
`wrap-stacktrace` | `stacktrace` | Cause and stacktrace analysis for exceptions.

## License

Copyright © 2013-2014 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.
