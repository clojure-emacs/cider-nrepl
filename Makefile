.PHONY: clean source-deps test-clj test-cljs eastwood cljfmt cloverage

VERSION ?= 1.9
export CLOVERAGE_VERSION = 1.0.11-SNAPSHOT

.source-deps:
	lein source-deps
	touch .source-deps

source-deps: .source-deps

test-clj: .source-deps
	lein with-profile +$(VERSION),+plugin.mranderson/config,+test-clj test

test-cljs: .source-deps
	lein with-profile +$(VERSION),+plugin.mranderson/config,+test-cljs test

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

