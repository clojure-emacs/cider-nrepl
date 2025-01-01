.PHONY: test quick-test eastwood cljfmt cljfmt-fix install fast-install smoketest deploy clean detect_timeout lint light-kondo docs test_impl
.DEFAULT_GOAL := quick-test

# Set bash instead of sh for the @if [[ conditions,
# and use the usual safety flags:
SHELL = /bin/bash -Ee

CLOJURE_VERSION ?= 1.12
TEST_PROFILES ?= "-user,-dev,+test"

# We need Java sources to test Java parsing functionality, but the Docker images
# we use on CircleCI doesn't include src.zip. So we have to download them from
# Github and repackage in a form that is resemblant to src.zip from normal
# distributions.
base-src-jdk8.zip:
	# echo 'Placeholder. We dont parse sources on JDK8.'
	touch $@

base-src-jdk11.zip:
	bash download-jdk-sources.sh https://github.com/adoptium/jdk11u/archive/refs/tags/jdk-11.0.25+9.zip jdk11 $@

base-src-jdk17.zip:
	bash download-jdk-sources.sh https://github.com/adoptium/jdk17u/archive/refs/tags/jdk-17.0.13+11.zip jdk17 $@

base-src-jdk21.zip:
	bash download-jdk-sources.sh https://github.com/adoptium/jdk21u/archive/refs/tags/jdk-21.0.5+3.zip jdk21 $@

base-src-jdk23.zip:
	bash download-jdk-sources.sh https://github.com/adoptium/jdk23u/archive/refs/tags/jdk-23.0.1+11.zip jdk23 $@

copy-sources-to-jdk: base-src-$(JDK_SRC_VERSION).zip
	mkdir -p $(JAVA_HOME)/lib && cp base-src-$(JDK_SRC_VERSION).zip $(JAVA_HOME)/lib/src.zip

dump-version:
	echo '"$(PROJECT_VERSION)"' > resources/cider/nrepl/version.edn

target/srcdeps: project.clj
	lein with-profile -user,-dev inline-deps
# Remove cljfmt.main because it depends on tools.cli which we explicitly removed.
	rm -f target/srcdeps/cider/nrepl/inlined/deps/cljfmt/*/cljfmt/main.clj

test_impl: copy-sources-to-jdk
		lein with-profile $(TEST_PROFILES),+$(CLOJURE_VERSION) test

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

docs:
	lein docs
