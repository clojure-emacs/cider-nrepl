.PHONY: test quick-test cljs-test inlined-test descriptor-contract smoketest copy-sources-to-jdk javac javac-test inline-deps jar install release deploy clean lint cljfmt cljfmt-fix kondo eastwood docs
.DEFAULT_GOAL := quick-test

SHELL = /bin/bash -Ee

CLOJURE_VERSION ?= 1.12
NREPL_VERSION ?= 1.7

# The lein plugin tests call leiningen.core.main/leiningen-version, which reads
# LEIN_VERSION from the env. Lein sets it automatically; under the clojure CLI
# we have to provide it ourselves.
export LEIN_VERSION := 2.11.2

# Aliases shared by the base test targets: the Clojure + nREPL version under
# test and the (non-inlined) shaded deps. ClojureScript is intentionally absent
# here (its 1.12 Closure compiler needs JDK21+, and the macroexpand middleware
# would try to load it); the cljs tests run separately via cljs-test.
TEST_ALIASES = :$(CLOJURE_VERSION):nrepl-$(NREPL_VERSION):inlined-deps:test

# We need Java sources to test Java parsing functionality, but the Docker images
# we use on CircleCI doesn't include src.zip. So we have to download them from
# Github and repackage in a form that is resemblant to src.zip from normal
# distributions.

download-jdk-src:
	clojure -T:build download-jdk-src

javac:
	clojure -T:build javac

javac-test:
	clojure -T:build javac :with-tests true

# Fast tests: run against the real (non-inlined) dependencies. The ClojureScript
# tests are excluded here and run separately (see cljs-test); ClojureScript is
# deliberately kept off the base classpath (see TEST_ALIASES above).
quick-test: javac-test download-jdk-src
	clojure -X$(TEST_ALIASES)

test: quick-test

# Audit that every op's documented :returns matches what it actually emits, by
# running the suite with the test session instrumented. Slower than the unit
# tests (it runs the whole op suite), so it's a separate target.
descriptor-contract: javac-test
	clojure -M:$(CLOJURE_VERSION):nrepl-$(NREPL_VERSION):inlined-deps:test -m cider.nrepl.descriptor-contract

# ClojureScript middleware tests. Kept separate because the CLJS 1.12 Closure
# compiler requires JDK21+, so CI only runs these on recent JDKs.
cljs-test: javac-test download-jdk-src
	clojure -X:cljs$(TEST_ALIASES) :dirs '["test/cljs"]'

inline-deps:
	clojure -T:build inline-deps

# Full tests: run the suite against the inlined/shaded sources (the equivalent
# of the old mranderson full-test job).
inlined-test: inline-deps javac-test
	clojure -X:$(CLOJURE_VERSION):nrepl-$(NREPL_VERSION):inlined:test

# PROJECT_VERSION=x.y.z make jar
jar: check-version
	clojure -T:build jar :version '"$(PROJECT_VERSION)"'

# PROJECT_VERSION=x.y.z make install
install: check-version
	clojure -T:build install :version '"$(PROJECT_VERSION)"'

# Build a standalone uberjar with the freshly installed cider-nrepl and run it,
# confirming the artifact loads and works outside this project's build.
smoketest: install
	cd test/smoketest && \
		clojure -T:build uber :cider-nrepl-version '"$(PROJECT_VERSION)"' && \
		java -jar target/smoketest-0.1.0-standalone.jar

clean:
	clojure -T:build clean

# Explicit paths so we don't recurse into the smoketest subproject or target dirs.
LINT_PATHS = src test/clj test/cljs test/common test/src build

cljfmt:
	clojure -M:cljfmt check $(LINT_PATHS)

cljfmt-fix:
	clojure -M:cljfmt fix $(LINT_PATHS)

kondo:
	clojure -M:kondo --lint src test/clj test/cljs test/common test/src

# Eastwood needs the shaded deps, ClojureScript and leiningen-core (for the lein
# plugin ns) on the classpath so the src namespaces can be analyzed, plus the
# compiled Java. Linting itself stays scoped to src (see the :eastwood alias).
eastwood: javac
	clojure -M:inlined-deps:cljs:test:eastwood

lint: cljfmt kondo eastwood

docs:
	clojure -X:docs :file '"doc/modules/ROOT/pages/nrepl-api/ops.adoc"' :version '"1.7.0"'

# Prepare a release: roll the changelog, bump the docs version, commit and tag.
# Pushing the tag (which triggers the deploy below) is left to you.
# PROJECT_VERSION=x.y.z make release
release: check-version
	clojure -T:build release :version '"$(PROJECT_VERSION)"'

# Deployment is performed via CI by creating a git tag prefixed with "v".
deploy: check-env
	@if ! echo "$(CIRCLE_TAG)" | grep -q "^v"; then \
		echo "[Error] CIRCLE_TAG $(CIRCLE_TAG) must start with 'v'."; \
		exit 1; \
	fi
	export PROJECT_VERSION=$$(echo "$(CIRCLE_TAG)" | sed 's/^v//'); \
	clojure -T:build deploy :version "\"$$PROJECT_VERSION\""

check-version:
ifndef PROJECT_VERSION
	$(error Please set PROJECT_VERSION as an env var beforehand.)
endif

check-env:
ifndef CLOJARS_USERNAME
	$(error CLOJARS_USERNAME is undefined)
endif
ifndef CLOJARS_PASSWORD
	$(error CLOJARS_PASSWORD is undefined)
endif
ifndef CIRCLE_TAG
	$(error CIRCLE_TAG is undefined. Please only perform deployments by publishing git tags.)
endif
