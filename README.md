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
`cider-nrepl` is also used by [fireplace.vim](https://github.com/tpope/vim-fireplace), [vim-iced](https://github.com/liquidz/vim-iced),
[Calva](https://calva.io/), [Conjure](https://github.com/Olical/conjure) and other Clojure development tools based on nREPL.

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

### Issues

Report issues and suggest features and improvements on the
[GitHub issue tracker](https://github.com/clojure-emacs/cider-nrepl/issues). Don't
ask questions on the issue tracker - the mailing list and the IRC
channel are the places for questions.

### Patches

Patches in any form are always welcome! GitHub pull requests are even better! :-)

Before submitting a patch or a pull request make sure all tests are
passing and that your patch is in line with the [contribution
guidelines](.github/CONTRIBUTING.md).

### Local development

Local development tasks, like firing up a repl, running the tests or locally installing cider-nrepl are offered by our Makefile.
We recommend using it, since some aspects can be intrincate to newcomers.

These are its main tasks for local development:

```
# Fire up a repl and nrepl server you can cider-connect to:
make repl

# Run tests, using mranderson (slower but more realistic)
PARSER_TARGET=parser-next make test

# Run tests, without using mranderson (considerably faster)
PARSER_TARGET=parser-next make fast-test

# Install the project in your local ~/.m2 directory, using mranderson (recommended)
# The JVM flag is a temporary workaround.
export LEIN_JVM_OPTS="-Dmranderson.internal.no-parallelism=true"
PROJECT_VERSION=0.43.1 make install

# Install the project in your local ~/.m2 directory, without using mranderson
# (it's faster, but please only use when you repeatedly need to install cider-nrepl)
# The JVM flag is a temporary workaround.
export LEIN_JVM_OPTS="-Dmranderson.internal.no-parallelism=true"
PROJECT_VERSION=0.43.1 make fast-install

# Runs clj-kondo, cljfmt and Eastwood (in that order, with fail-fast).
# Please try to run this before pushing commits.
make lint

# Regenerates our user manual.
# When you modify our middleware such that its schema changes, please reflect so in the `cider.nrepl` namespace
# and run:
make docs
```

## Releasing to Clojars

**Note:** Make sure you've updated the changelog, `docs/antora.yml`, etc
before cutting a new release.

Release to [clojars](https://clojars.org/) by tagging a new release:

```
git tag -a v0.43.1 -m "Release 0.43.1"
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
