.PHONY: clean source-deps test-clj test-cljs eastwood cljfmt cloverage

VERSION ?= 1.9
export CLOVERAGE_VERSION = 1.0.11-SNAPSHOT

# The test-cljs target needs to be modified if working with JDK9
JAVA_VERSION = $(shell lein version | cut -d " " -f 5 | cut -d "." -f 1-2)

.source-deps:
	lein source-deps
	touch .source-deps

source-deps: .source-deps

test-clj: .source-deps
	lein with-profile +$(VERSION),+test-clj test

test-cljs: .source-deps
	if [ "$(JAVA_VERSION)" = "9.0" ] || [ "$(JAVA_VERSION)" = "9-internal" ]; then \
            lein with-profile +$(VERSION),+test-cljs \
                 update-in :jvm-opts concat '["--add-modules" "java.xml.bind"]' \
                 -- test; \
        else \
            lein with-profile +$(VERSION),+test-cljs test; \
	fi

eastwood: .source-deps
	lein with-profile +$(VERSION),+test-clj,+test-cljs,+eastwood eastwood

cljfmt: .source-deps
	lein with-profile +$(VERSION),+test-clj,+test-cljs,+cljfmt cljfmt check


# Cloverage can't handle some of the code in this project.  For now we
# must filter problematic namespaces (`-e`) and tests (`-t`) from
# instrumentation. Note: this means for now coverage reporting isn't
# exact. See issue #457 for details.

cloverage: .source-deps
	lein with-profile +$(VERSION),+test-clj,+cloverage cloverage --codecov \
	     -e ".*java.parser" \
	     -e "cider-nrepl.plugin" \
	     -e ".*util.instrument" \
	     -t "^((?!debug-integration-test).)*$$"

clean:
	lein clean
	rm .source-deps

