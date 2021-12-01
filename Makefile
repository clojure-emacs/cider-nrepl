.PHONY: test eastwood cljfmt install smoketest release deploy clean detect_timeout

CLOJURE_VERSION ?= 1.10

test/resources/cider/nrepl/clojuredocs/export.edn:
	curl -o $@ https://github.com/clojure-emacs/clojuredocs-export-edn/raw/master/exports/export.compact.edn

.inline-deps: clean
	lein with-profile -user,-dev inline-deps
	touch .inline-deps

inline-deps: .inline-deps

test: clean .inline-deps test/resources/cider/nrepl/clojuredocs/export.edn
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+test,+plugin.mranderson/config test

quick-test: clean
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+test test

tools-deps-test: clean install
	cd tools-deps-testing; clojure -M:test

eastwood:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+eastwood eastwood

cljfmt:
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+cljfmt cljfmt check

kondo:
	lein with-profile -user,-dev,+clj-kondo run -m clj-kondo.main --lint src

install: .inline-deps
	lein with-profile -user,-dev,+$(CLOJURE_VERSION),+plugin.mranderson/config install

smoketest: install
	cd test/smoketest && \
        lein with-profile -user,-dev,+$(CLOJURE_VERSION) uberjar && \
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
	lein with-profile -user,-dev,+$(CLOJURE_VERSION) release $(BUMP)

# Deploying requires the caller to set environment variables as
# specified in project.clj to provide a login and password to the
# artifact repository.

deploy: .inline-deps
	lein with-profile -user,+$(CLOJURE_VERSION),+plugin.mranderson/config deploy clojars

clean:
	lein clean
	cd test/smoketest && lein clean
	rm -f .inline-deps
