[![CircleCI](https://circleci.com/gh/clojure-emacs/cider-nrepl/tree/master.svg?style=svg)](https://circleci.com/gh/clojure-emacs/cider-nrepl/tree/master)
[![Clojars Project](https://img.shields.io/clojars/v/cider/cider-nrepl.svg)](https://clojars.org/cider/cider-nrepl)
[![Dependencies Status](https://versions.deps.co/clojure-emacs/cider-nrepl/status.svg)](https://versions.deps.co/clojure-emacs/cider-nrepl)
[![Coverage](https://codecov.io/gh/clojure-emacs/cider-nrepl/branch/master/graph/badge.svg)](https://codecov.io/gh/clojure-emacs/cider-nrepl/)
[![cljdoc badge](https://cljdoc.org/badge/cider/cider-nrepl)](https://cljdoc.org/d/cider/cider-nrepl/CURRENT)
[![downloads badge](https://versions.deps.co/cider/cider-nrepl/downloads.svg)](https://clojars.org/cider/cider-nrepl)

# CIDER nREPL

A collection of [nREPL](https://github.com/nrepl/nrepl)
middleware, originally designed to enhance
[CIDER](https://github.com/clojure-emacs/cider).
`cider-nrepl` is also used by `vim-fireplace`, `iced-vim`,
Calva and other Clojure development tools based on nREPL.

If you're just a user trying to get started with CIDER, then you
probably don't want to read this. You should follow the steps in
[CIDER's user manual](https://docs.cider.mx) instead.

People who are new to nREPL might benefit from reading [its
user manual](https://nrepl.org).

## Documentation

Please refer to the [documentation site](https://docs.cider.mx/cider-nrepl) for
information on how to setup `cider-nrepl` or how to leverage its nREPL API.

## Release policy

We're following [SemVer](http://semver.org/) (as much as one can be
following it when the major version is 0). At this point bumps of the
minor (second) version number are considered major releases and always
include new features or significant changes to existing features. API
compatibility between major releases is not a (big) concern (although we try
to break the API rarely and only for a good reason).

The development cycle for the next major
release starts immediately after the previous one has been
shipped. Bugfix/point releases (if any) address only serious bugs and
never contain new features.

**Note:** Prior to cider-nrepl 0.18, CIDER and cider-nrepl releases
were always done in sync. As most editors started relying on cider-nrepl
this was eventually changed and now cider-nrepl releases happen on their
own schedule.

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
lein do clean, inline-deps
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
lein with-profile +plugin.mranderson/config install
```

#### Using the Makefile

...Or you can use the `Makefile` as:

```
PROJECT_VERSION=0.30.0 make install
```

## Releasing to Clojars

**Note:** Make sure you've updated the changelog, `docs/antora.yml`, etc
before cutting a new release.

Release to [clojars](https://clojars.org/) by tagging a new release:

```
git tag -a v0.30.0 -m "Release 0.30.0"
git push --tags
```

The CI will take it from there.

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
* [clj-suitable][] - for ClojureScript code completion using runtime inspection
* [tools.trace][] - for tracing
* [tools.namespace][] - for namespace reloading
* [cljfmt][] - for code formatting

## License

Copyright © 2013-2023 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.

[orchard]: https://github.com/clojure-emacs/orchard
[compliment]: https://github.com/alexander-yakushev/compliment
[clj-suitable]: https://github.com/clojure-emacs/clj-suitable
[tools.trace]: https://github.com/clojure/tools.trace
[tools.namespace]: https://github.com/clojure/tools.namespace
[cljfmt]: https://github.com/weavejester/cljfmt
[vim-replant]: https://github.com/SevereOverfl0w/vim-replant
[vim-fireplace]: https://github.com/tpope/vim-fireplace
[mranderson]: https://github.com/benedekfazekas/mranderson
