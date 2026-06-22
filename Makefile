.PHONY: test quick-test cljs-test inlined-test smoketest copy-sources-to-jdk javac javac-test inline-deps jar install deploy clean lint cljfmt cljfmt-fix kondo eastwood docs
.DEFAULT_GOAL := quick-test

SHELL = /bin/bash -Ee

CLOJURE_VERSION ?= 1.12
NREPL_VERSION ?= 1.7
# The lein plugin tests call leiningen.core.main/leiningen-version, which reads
# LEIN_VERSION from the env. Lein sets it automatically; under the clojure CLI
# we have to provide it ourselves.
LEIN_VERSION ?= 2.11.2

# Aliases shared by the base test targets: the Clojure + nREPL version under
# test and the (non-inlined) shaded deps. ClojureScript is intentionally absent
# here (its 1.12 Closure compiler needs JDK21+, and the macroexpand middleware
# would try to load it); the cljs tests run separately via cljs-test.
TEST_ALIASES = :$(CLOJURE_VERSION):nrepl-$(NREPL_VERSION):inlined-deps:test

# We need Java sources to test Java parsing functionality, but the Docker images
# we use on CircleCI don't ship src.zip. So we download them from GitHub and
# repackage them in a form resembling a normal distribution's src.zip. This is
# only meant to run on CI; locally you already have a real src.zip.
base-src-jdk8.zip:
	# We don't parse sources on JDK8.
	touch $@

base-src-jdk11.zip:
	bash .circleci/download-jdk-sources.sh https://github.com/adoptium/jdk11u/archive/refs/tags/jdk-11.0.28+0.zip jdk11 $@

base-src-jdk17.zip:
	bash .circleci/download-jdk-sources.sh https://github.com/adoptium/jdk17u/archive/refs/tags/jdk-17.0.15+5.zip jdk17 $@

base-src-jdk21.zip:
	bash .circleci/download-jdk-sources.sh https://github.com/adoptium/jdk21u/archive/refs/tags/jdk-21.0.7+5.zip jdk21 $@

base-src-jdk25.zip:
	bash .circleci/download-jdk-sources.sh https://github.com/adoptium/jdk/archive/refs/tags/jdk-25+36.zip jdk25 $@

copy-sources-to-jdk: base-src-$(JDK_SRC_VERSION).zip
	mkdir -p $(JAVA_HOME)/lib && cp base-src-$(JDK_SRC_VERSION).zip $(JAVA_HOME)/lib/src.zip

javac:
	clojure -T:build javac

javac-test:
	clojure -T:build javac :with-tests true

# Fast tests: run against the real (non-inlined) dependencies. The ClojureScript
# tests are excluded here and run separately (see cljs-test); ClojureScript is
# deliberately kept off the base classpath (see TEST_ALIASES above).
quick-test: javac-test
	LEIN_VERSION=$(LEIN_VERSION) clojure -X$(TEST_ALIASES)

test: quick-test

# ClojureScript middleware tests. Kept separate because the CLJS 1.12 Closure
# compiler requires JDK21+, so CI only runs these on recent JDKs.
cljs-test: javac-test
	LEIN_VERSION=$(LEIN_VERSION) clojure -X$(TEST_ALIASES):cljs :dirs '["test/cljs"]'

inline-deps:
	clojure -T:build inline-deps

# Full tests: run the suite against the inlined/shaded sources (the equivalent
# of the old mranderson full-test job).
inlined-test: inline-deps javac-test
	LEIN_VERSION=$(LEIN_VERSION) clojure -X:$(CLOJURE_VERSION):nrepl-$(NREPL_VERSION):inlined:test

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
	clojure -M:maint -m cider.nrepl.impl.docs --file doc/modules/ROOT/pages/nrepl-api/ops.adoc

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
