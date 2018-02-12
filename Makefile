.PHONY: test-clj test-cljs eastwood cljfmt cloverage release deploy clean

VERSION ?= 1.9
export CLOVERAGE_VERSION = 1.0.11-SNAPSHOT

# The test-cljs target needs to be modified if working with JDK9
JAVA_VERSION = $(shell lein with-profile +sysutils \
                       sysutils :java-version-simple | cut -d " " -f 2)

.source-deps:
	lein source-deps
	touch .source-deps

source-deps: .source-deps

test-clj: .source-deps
	lein with-profile +$(VERSION),+plugin.mranderson/config,+test-clj test

test-cljs: .source-deps
	if [ "$(JAVA_VERSION)" = "9" ]; then \
            lein with-profile +$(VERSION),+plugin.mranderson/config,+test-cljs \
                 update-in :jvm-opts concat '["--add-modules" "java.xml.bind"]' \
                 -- test; \
        else \
            lein with-profile +$(VERSION),+plugin.mranderson/config,+test-cljs test; \
	fi

eastwood:
	lein with-profile +$(VERSION),+test-clj,+test-cljs,+eastwood eastwood \
	     "{:namespaces [:source-paths] :exclude-namespaces [cider-nrepl.plugin]}"

cljfmt:
	lein with-profile +$(VERSION),+test-clj,+test-cljs,+cljfmt cljfmt check


# Cloverage can't handle some of the code in this project.  For now we
# must filter problematic namespaces (`-e`) and tests (`-t`) from
# instrumentation. Note: this means for now coverage reporting isn't
# exact. See issue #457 for details.

cloverage:
	lein with-profile +$(VERSION),+test-clj,+cloverage cloverage --codecov \
	     -e ".*java.parser" \
	     -e "cider-nrepl.plugin" \
	     -e ".*util.instrument" \
	     -t "^((?!debug-integration-test).)*$$"

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile +$(VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy: .source-deps
	lein with-profile +$(VERSION),+plugin.mranderson/config deploy clojars

clean:
	lein clean
	rm -f .source-deps
