[![CircleCI](https://img.shields.io/circleci/build/github/clojure-emacs/cider-nrepl/master.svg)](https://circleci.com/gh/clojure-emacs/cider-nrepl/tree/master)
[![Coverage](https://codecov.io/gh/clojure-emacs/cider-nrepl/branch/master/graph/badge.svg)](https://codecov.io/gh/clojure-emacs/cider-nrepl/)
[![Clojars Project](https://img.shields.io/clojars/v/cider/cider-nrepl.svg)](https://clojars.org/cider/cider-nrepl)
[![cljdoc badge](https://cljdoc.org/badge/cider/cider-nrepl)](https://cljdoc.org/d/cider/cider-nrepl/CURRENT)
[![Downloads](https://img.shields.io/clojars/dt/cider/cider-nrepl?color=cornflowerblue)](https://clojars.org/cider/cider-nrepl)

# CIDER nREPL

A collection of [nREPL](https://github.com/nrepl/nrepl)
middleware, originally designed to enhance
[CIDER](https://github.com/clojure-emacs/cider).
`cider-nrepl` is also used:

- [fireplace.vim](https://github.com/tpope/vim-fireplace)
- [vim-iced](https://github.com/liquidz/vim-iced)
- [Calva](https://calva.io/)
- [Conjure](https://github.com/Olical/conjure)

and other Clojure development tools based on nREPL.

If you're just a user trying to get started with CIDER, then you
probably don't want to read this. You should follow the steps in
[CIDER's user manual](https://docs.cider.mx) instead.

> [!TIP]
>
> People who are new to nREPL might benefit from reading [its
> user manual](https://nrepl.org).

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

Local development tasks, like running the tests or locally installing
cider-nrepl are offered by our Makefile.  We recommend using it, since some
aspects (e.g. the use of [mranderson][]) can be intricate to newcomers.

These are its main tasks for local development:

```shell
# Run tests, using mranderson (slower but more realistic)
make test

# Run tests, without using mranderson (considerably faster)
make quick-test

# Install the project in your local ~/.m2 directory, using mranderson (recommended)
# The JVM flag is a temporary workaround.
export LEIN_JVM_OPTS="-Dmranderson.internal.no-parallelism=true"
PROJECT_VERSION=X.Y.Z make install

# Install the project in your local ~/.m2 directory, without using mranderson
# (it's faster, but please only use when you repeatedly need to install cider-nrepl)
# The JVM flag is a temporary workaround.
export LEIN_JVM_OPTS="-Dmranderson.internal.no-parallelism=true"
PROJECT_VERSION=X.Y.Z make fast-install

# Runs clj-kondo, cljfmt and Eastwood (in that order, with fail-fast).
# Please try to run this before pushing commits.
make lint

# Regenerates our user manual.
# When you modify our middleware such that its schema changes, please reflect so in the `cider.nrepl` namespace
# and run:
make docs
```

## Releasing to Clojars

> [!IMPORTANT]
>
> Make sure you've updated the changelog, `docs/antora.yml`, etc
> before cutting a new release.

Release to [clojars](https://clojars.org/) by tagging a new release:

```shell
git tag -a vX.Y.Z -m "Release X.Y.Z"
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

- [orchard][] - extracted from `cider-nrepl`, so that non-nREPL clients can leverage the generic tooling functionality (like `inspect`, `apropos`, `var-info`, etc
- [compliment][] - for Clojure code completion
- [clj-suitable][] - for ClojureScript code completion using runtime inspection
- [tools.namespace][] - for namespace reloading
- [clj-reload][] - for namespace reloading
- [cljfmt][] - for code formatting

## License

Copyright © 2013-2025 Bozhidar Batsov

Distributed under the Eclipse Public License, the same as Clojure.

[orchard]: https://github.com/clojure-emacs/orchard
[compliment]: https://github.com/alexander-yakushev/compliment
[clj-suitable]: https://github.com/clojure-emacs/clj-suitable
[tools.namespace]: https://github.com/clojure/tools.namespace
[clj-reload]: https://github.com/tonsky/clj-reload
[cljfmt]: https://github.com/weavejester/cljfmt
[mranderson]: https://github.com/benedekfazekas/mranderson
