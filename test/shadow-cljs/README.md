# shadow-cljs sample project

A minimal [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
project kept around as a fixture for exercising cider-nrepl's ClojureScript
support against a real shadow setup (completion, info, eldoc, etc.).

It is intentionally tiny and not wired into the main test suite - it needs Node
and a shadow-cljs toolchain download, so it's meant for manual/exploratory runs.

## Layout

- `shadow-cljs.edn` - two builds:
  - `:app` - the canonical browser "Hello World" from the UsersGuide.
  - `:node` - a `:node-script` build that gives a headless cljs REPL (no browser
    needed), which is what makes this convenient to drive from a script.
- `src/main/shadow_sample/` - the sample namespaces.
- `src/dev/cljs_env_probe.clj` - a diagnostic nREPL middleware (see below).

## Setup

```sh
npm install            # pulls the shadow-cljs npm wrapper
npx shadow-cljs compile :node   # first run downloads the JVM toolchain
```

## Headless REPL (the easy way to poke at it)

```sh
npx shadow-cljs watch :node     # compiles, starts nREPL, writes .shadow-cljs/nrepl.port
node out/node-main.js           # in another shell: connects a runtime back to shadow
```

Connect any nREPL client to the port in `.shadow-cljs/nrepl.port`, then enter
the cljs REPL for the `:node` build by evaluating:

```clojure
(shadow.cljs.devtools.api/nrepl-select :node)
```

From there, evals run in ClojureScript (`cljs.user`).

## The cljs-env probe

`src/dev/cljs_env_probe.clj` is wired into `shadow-cljs.edn`'s `:nrepl`
middleware. For every session-bearing message it appends a diagnostic map to
`/tmp/cljs-env-probe.log` describing how reachable the session's ClojureScript
compiler env is - both via shadow's own `*repl-state*` / `api/compiler-env` and
via the piggieback `*cljs-compiler-env*` var.

It exists to answer one question: when a session is in a shadow cljs REPL, can
cider-nrepl's static-analysis ops (info/complete/eldoc) reach the compiler env
*outside of eval*? Tail the log while you drive the REPL:

```sh
tail -f /tmp/cljs-env-probe.log
```

### What we found

With shadow-cljs 3.4.11, on non-eval ops (`info`, `eldoc`) while a `:node` cljs
REPL is active, the probe reports:

```clojure
{:build-id :node
 :repl-state-in-session?        true
 :compiler-env-found?           true   ; via shadow.cljs.devtools.api/compiler-env
 :compiler-env-has-namespaces?  true
 :piggieback-var-in-session?    true   ; cider.piggieback/*cljs-compiler-env*
 :piggieback-env-has-namespaces? true}
```

The key takeaway: **shadow-cljs depends on `cider/piggieback` transitively** (it
is not declared anywhere in this project) and its `nrepl-select` populates
piggieback's `*cljs-compiler-env*` session var. So cider-nrepl's existing,
piggieback-based `grab-cljs-env` already finds shadow's compiler env - both the
shadow-native path and the piggieback path resolve the same live env on ordinary
non-eval messages.
