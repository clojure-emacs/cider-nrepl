.PHONY: test quick-test eastwood cljfmt cljfmt-fix install fast-install smoketest deploy clean detect_timeout lein-repl repl lint light-kondo docs test_impl
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
SHELL = /bin/bash -Ee

# We need Java sources to test Java parsing functionality, but the Docker images
# we use on CircleCI doesn't include src.zip. So we have to download them from
# Github and repackage in a form that is resemblant to src.zip from normal
# distributions.
base-src.zip:
	wget https://github.com/adoptium/jdk21u/archive/refs/tags/jdk-21.0.5+3.zip -O full-src.zip
	unzip -q full-src.zip
	cp -r jdk21u-*/src/java.base/share/classes java.base
	cp -r jdk21u-*/src/java.desktop/share/classes java.desktop
	zip -qr base-src.zip java.base java.desktop
	rm -rf java.base java.desktop jdk21u-* full-src.zip

dump-version:
	echo '"$(PROJECT_VERSION)"' > resources/cider/nrepl/version.edn

target/srcdeps: project.clj
	lein with-profile -user,-dev inline-deps
# Remove cljfmt.main because it depends on tools.cli which we explicitly removed.
	rm -f target/srcdeps/cider/nrepl/inlined/deps/cljfmt/*/cljfmt/main.clj

test_impl: base-src.zip
	@if [[ "$$PARSER_TARGET" == "parser-next" ]] ; then \
		lein with-profile $(TEST_PROFILES),+$(CLOJURE_VERSION),+parser-next test; \
	else \
		lein with-profile $(TEST_PROFILES),+$(CLOJURE_VERSION) test; \
	fi

test: target/srcdeps
	@make test_impl TEST_PROFILES="$(TEST_PROFILES),+plugin.mranderson/config"

quick-test: test_impl

eastwood:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+deploy,+eastwood eastwood

cljfmt:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+cljfmt cljfmt check

cljfmt-fix:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+cljfmt cljfmt fix

.make_kondo_prep: project.clj .clj-kondo/config.edn
	CIDER_NO_MRANDERSON="true" CIDER_NO_PEDANTIC="true" lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo --copy-configs --dependencies --lint '$$classpath' > $@

kondo: .make_kondo_prep clean
	CIDER_NO_MRANDERSON="true" CIDER_NO_PEDANTIC="true" lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo

# A variation that does not analyze the classpath, as it OOMs otherwise on CircleCI.
light-kondo: clean
	CIDER_NO_MRANDERSON="true" CIDER_NO_PEDANTIC="true" lein with-profile -user,-dev,+test,+clj-kondo,+deploy,+$(CLOJURE_VERSION) clj-kondo

lint: kondo cljfmt eastwood

# PROJECT_VERSION=x.y.z make install
install: dump-version check-install-env target/srcdeps
	CIDER_NO_PEDANTIC="true" lein with-profile -user,-dev,+$(CLOJURE_VERSION),+plugin.mranderson/config install
	git checkout resources/cider/nrepl/version.edn

# PROJECT_VERSION=x.y.z make fast-install
fast-install: dump-version check-install-env
	lein with-profile -user,-dev,+$(CLOJURE_VERSION) install
	git checkout resources/cider/nrepl/version.edn

smoketest: install
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
deploy: check-env target/srcdeps
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
