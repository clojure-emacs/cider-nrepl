# CIDER nREPL

A collection of nREPL middleware designed to enhance CIDER.

## Usage

Use the convenient plugin for defaults, either in your project's `project.clj` file or in the `:user` profile in `~/.lein/profiles.clj`.

```clojure
:plugins [[cider/cider-nrepl "0.1.0-SNAPSHOT"]]
```

Or add `cider-nrepl` to your `:dev :dependencies` vector plus specific middleware to to `:nrepl-middleware` under `:repl-options`.

```clojure
:dependencies [[cider/cider-nrepl "0.1.0-SNAPSHOT"]]
:repl-options {:nrepl-middleware
                [cider.nrepl.middleware.doc/wrap-doc
                 cider.nrepl.middleware.complete/wrap-complete
                 cider.nrepl.middleware.info/wrap-info]}
```


## Supported nREPL ops

"doc" : Returns the doc string for the specified symbol.

"complete" : Simple completion.

"info" : File/line, arglists, docstrings and other metadata for symbols.

## License

Copyright © 2013 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.
