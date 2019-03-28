.PHONY: test eastwood cljfmt cloverage install smoketest release deploy clean detect_timeout

CLOJURE_VERSION ?= 1.9
export CLOVERAGE_VERSION = 1.0.13

JAVA_VERSION = $(shell lein with-profile +sysutils \
                       sysutils :java-version-simple | cut -d " " -f 2)

.inline-deps:
	lein inline-deps
	touch .inline-deps

inline-deps: .inline-deps

test: .inline-deps
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config test

eastwood:
	lein with-profile +$(CLOJURE_VERSION),+eastwood eastwood

cljfmt:
	lein with-profile +$(CLOJURE_VERSION),+cljfmt cljfmt check

cloverage:
	lein with-profile +$(CLOJURE_VERSION),+cloverage cloverage

install: .inline-deps
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config install

smoketest: install
	cd test/smoketest && \
        lein with-profile +$(CLOJURE_VERSION) uberjar && \
        java -jar target/smoketest-0.1.0-SNAPSHOT-standalone.jar


# Run a background process that prints all JVM stacktraces after five minutes,
# then kills all JVMs, to help diagnose issues with ClojureScript tests getting
# stuck.
detect_timeout:
	(bin/ci_detect_timeout &)

# When releasing, the BUMP variable controls which field in the
# version string will be incremented in the *next* snapshot
# version. Typically this is either "major", "minor", or "patch".

BUMP ?= patch

release:
	lein with-profile +$(CLOJURE_VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy: .inline-deps
	lein with-profile +$(CLOJURE_VERSION),+plugin.mranderson/config deploy clojars

clean:
	lein clean
	cd test/smoketest && lein clean
	rm -f .inline-deps
