.PHONY: test-clj test-cljs eastwood cljfmt cloverage install smoketest release deploy clean check_node

CLOJURE_VERSION ?= 1.9
export CLOVERAGE_VERSION = 1.0.13

# The test-cljs target needs to be modified if working with JDK9
JAVA_VERSION = $(shell lein with-profile +sysutils \
                       sysutils :java-version-simple | cut -d " " -f 2)

.source-deps:
	lein source-deps
	touch .source-deps

source-deps: .source-deps

test-clj: .source-deps smoketest
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config,+test-clj test

test-cljs: .source-deps check_node
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config,+test-cljs test

eastwood:
	lein with-profile +$(CLOJURE_VERSION),+test-clj,+test-cljs,+eastwood eastwood \
	     "{:namespaces [:source-paths] :exclude-namespaces [cider-nrepl.plugin]}"

cljfmt:
	lein with-profile +$(CLOJURE_VERSION),+test-clj,+test-cljs,+cljfmt cljfmt check


# Cloverage can't handle some of the code in this project.  For now we
# must filter problematic namespaces (`-e`) and tests (`-t`) from
# instrumentation. Note: this means for now coverage reporting isn't
# exact. See issue #457 for details.

cloverage:
	lein with-profile +$(CLOJURE_VERSION),+test-clj,+cloverage cloverage --codecov \
	     -e ".*java.parser" \
	     -e "cider-nrepl.plugin" \
	     -e ".*util.instrument" \
	     -t "^((?!debug-integration-test).)*$$"

install: .source-deps
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config install

smoketest: install
	cd test/smoketest && \
        lein with-profile +$(CLOJURE_VERSION) uberjar && \
        java -jar target/smoketest-0.1.0-SNAPSHOT-standalone.jar

check_node:
	which node

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile +$(CLOJURE_VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy: .source-deps
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config deploy clojars

clean:
	lein clean
	cd test/smoketest && lein clean
	rm -f .source-deps
