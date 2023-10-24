.PHONY: test quick-test fast-test eastwood cljfmt install fast-install smoketest deploy clean detect_timeout lein-repl repl lint light-kondo docs test_impl
.DEFAULT_GOAL := quick-test

CLOJURE_VERSION ?= 1.11

# The Lein profiles that will be selected for `lein-repl`.
# Feel free to upgrade this, or to override it with an env var named LEIN_PROFILES.
# Expected format: "+dev,+test"
# Don't use spaces here.
LEIN_PROFILES ?= "+dev,+test"

TEST_PROFILES ?= "-user,-dev,+test"

# The enrich-classpath version to be injected.
# Feel free to upgrade this.
ENRICH_CLASSPATH_VERSION="1.18.2"

# Set bash instead of sh for the @if [[ conditions,
# and use the usual safety flags:
SHELL = /bin/bash -Eeu

ifeq ($(OS),Darwin) # macOS
	SED_INPLACE = -i ''
else
	SED_INPLACE = -i
endif

TEST_RUNNER_SOURCE_DIR = test-runner

$(TEST_RUNNER_SOURCE_DIR):
	@if [ ! -d "$(TEST_RUNNER_SOURCE_DIR)" ]; then git clone https://github.com/cognitect-labs/test-runner.git $(TEST_RUNNER_SOURCE_DIR) --depth=1; fi

test/resources/cider/nrepl/clojuredocs/export.edn:
	curl -o $@ https://github.com/clojure-emacs/clojuredocs-export-edn/raw/master/exports/export.compact.edn

dump-version:
	echo '"$(PROJECT_VERSION)"' > resources/cider/nrepl/version.edn

.inline-deps: project.clj clean
	rm -f .no-mranderson
	lein with-profile -user,-dev inline-deps
	touch $@

test_impl: $(TEST_RUNNER_SOURCE_DIR) test/resources/cider/nrepl/clojuredocs/export.edn
	rm -f .no-mranderson
	@if [[ "$$PARSER_TARGET" == "parser-next" ]] ; then \
		export SKIP_INLINING_TEST_DEPS=true; \
		bash 'lein' 'update-in' ':plugins' 'conj' "[mx.cider/lein-enrich-classpath \"$(ENRICH_CLASSPATH_VERSION)\"]" '--' 'with-profile' $(TEST_PROFILES),+cognitest,+$(CLOJURE_VERSION) 'update-in' ':middleware' 'conj' 'cider.enrich-classpath.plugin-v2/middleware' '--' 'repl' | grep " -cp " > .test-classpath; \
		cat .test-classpath; \
		eval "$$(cat .test-classpath)"; \
		rm .test-classpath; \
	elif [[ "$$PARSER_TARGET" == "parser" ]] ; then \
		export SKIP_INLINING_TEST_DEPS=true; \
		bash 'lein' 'update-in' ':plugins' 'conj' "[mx.cider/lein-enrich-classpath \"$(ENRICH_CLASSPATH_VERSION)\"]" '--' 'with-profile' $(TEST_PROFILES),+cognitest,+$(CLOJURE_VERSION) 'update-in' ':middleware' 'conj' 'cider.enrich-classpath.plugin-v2/middleware' '--' 'repl' | grep " -cp " > .test-classpath; \
		cat .test-classpath; \
		sed $(SED_INPLACE) 's/--add-opens=jdk.compiler\/com.sun.tools.javac.code=ALL-UNNAMED//g' .test-classpath; \
		sed $(SED_INPLACE) 's/--add-opens=jdk.compiler\/com.sun.tools.javac.tree=ALL-UNNAMED//g' .test-classpath; \
		cat .test-classpath; \
		eval "$$(cat .test-classpath)"; \
		rm .test-classpath; \
	elif [[ "$$PARSER_TARGET" == "legacy-parser" ]] ; then \
		export SKIP_INLINING_TEST_DEPS=true; \
		lein with-profile +$(CLOJURE_VERSION),$(TEST_PROFILES) test; \
	else \
		echo "PARSER_TARGET unset!"; \
		exit 1; \
	fi

test: clean .inline-deps
	@make test_impl TEST_PROFILES="$(TEST_PROFILES),+plugin.mranderson/config"

quick-test: clean test_impl

fast-test: quick-test

tools-deps-test: clean install
	cd tools-deps-testing; clojure -M:test

eastwood:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+deploy,+eastwood eastwood

cljfmt:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+cljfmt cljfmt check

.make_kondo_prep: project.clj .clj-kondo/config.edn
	touch .no-pedantic
	touch .no-mranderson
	lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo --copy-configs --dependencies --lint '$$classpath' > $@
	rm -f .no-pedantic
	rm -f .no-mranderson

kondo: .make_kondo_prep clean
	touch .no-pedantic
	touch .no-mranderson
	lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo
	rm -f .no-pedantic
	rm -f .no-mranderson

# A variation that does not analyze the classpath, as it OOMs otherwise on CircleCI.
light-kondo: clean
	touch .no-pedantic
	touch .no-mranderson
	lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo
	rm -f .no-pedantic
	rm -f .no-mranderson

lint: kondo cljfmt eastwood

# PROJECT_VERSION=0.41.0 make install
install: dump-version check-install-env .inline-deps
	rm -f .no-mranderson
	touch .no-pedantic
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+plugin.mranderson/config install
	touch .no-pedantic
	make clean
	git checkout resources/cider/nrepl/version.edn

# PROJECT_VERSION=0.41.0 make fast-install
fast-install: dump-version check-install-env
	lein with-profile -user,-dev,+$(CLOJURE_VERSION) install
	make clean
	git checkout resources/cider/nrepl/version.edn

smoketest:
	export SKIP_INLINING_TEST_DEPS=true
	make install
	cd test/smoketest && \
        lein with-profile -user,-dev,+$(CLOJURE_VERSION) uberjar && \
        java -jar target/smoketest-0.1.0-SNAPSHOT-standalone.jar

# Run a background process that prints all JVM stacktraces after five minutes,
# then kills all JVMs, to help diagnose issues with ClojureScript tests getting
# stuck.
detect_timeout:
	(bin/ci_detect_timeout &)

# Deployment is performed via CI by creating a git tag prefixed with "v".
# Please do not deploy locally as it skips various measures (particularly around mranderson).
deploy: check-env .inline-deps
	rm -f .no-mranderson
	lein with-profile -user,+$(CLOJURE_VERSION),+plugin.mranderson/config deploy clojars

check-env:
ifndef CLOJARS_USERNAME
	$(error CLOJARS_USERNAME is undefined)
endif
ifndef CLOJARS_PASSWORD
	$(error CLOJARS_PASSWORD is undefined)
endif
ifndef CIRCLE_TAG
	$(error CIRCLE_TAG is undefined. Please only perform deployments by publishing git tags. CI will do the rest.)
endif

check-install-env:
ifndef PROJECT_VERSION
	$(error Please set PROJECT_VERSION as an env var beforehand.)
endif

clean:
	lein with-profile -user clean
	cd test/smoketest && lein with-profile -user clean
	rm -f .inline-deps

# Create and cache a `java` command. project.clj is mandatory; the others are optional but are taken into account for cache recomputation.
# It's important not to silence with step with @ syntax, so that Enrich progress can be seen as it resolves dependencies.
.enrich-classpath-lein-repl: Makefile project.clj $(wildcard checkouts/*/project.clj) $(wildcard deps.edn) $(wildcard $(HOME)/.clojure/deps.edn) $(wildcard profiles.clj) $(wildcard $(HOME)/.lein/profiles.clj) $(wildcard $(HOME)/.lein/profiles.d) $(wildcard /etc/leiningen/profiles.clj)
	bash 'lein' 'update-in' ':plugins' 'conj' "[mx.cider/lein-enrich-classpath \"$(ENRICH_CLASSPATH_VERSION)\"]" '--' 'with-profile' $(LEIN_PROFILES) 'update-in' ':middleware' 'conj' 'cider.enrich-classpath.plugin-v2/middleware' '--' 'repl' | grep " -cp " > $@

# Launches a repl, falling back to vanilla lein repl if something went wrong during classpath calculation.
lein-repl: .enrich-classpath-lein-repl
	@if grep --silent " -cp " .enrich-classpath-lein-repl; then \
		export YOURKIT_SESSION_NAME="$(basename $(PWD))"; \
		eval "$$(cat .enrich-classpath-lein-repl) --interactive"; \
	else \
		echo "Falling back to lein repl... (you can avoid further falling back by removing .enrich-classpath-lein-repl)"; \
		lein with-profiles $(LEIN_PROFILES) repl; \
	fi

repl: lein-repl

docs:
	lein docs
