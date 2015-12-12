#!/bin/bash

# runs source-deps and tests and then provided lein target with mranderson

function check_result {
    if [ $? -ne 0 ]; then
        echo "FAILED"
        exit 1
    fi
}

lein do clean, source-deps
check_result
lein with-profile +1.7,+plugin.mranderson/config,+test-clj,+test-cljs test
check_result
lein with-profile plugin.mranderson/config "$@"
