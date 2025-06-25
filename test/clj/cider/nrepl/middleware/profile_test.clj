(ns cider.nrepl.middleware.profile-test
  (:require
   [cider.nrepl.test-session :as session]
   [cider.test-helpers :refer :all]
   [clojure.test :refer :all]
   [matcher-combinators.matchers :as mc]
   [orchard.profile]))

(defn- with-clear-and-unprofile [f]
  (orchard.profile/clear)
  (orchard.profile/unprofile-all)
  (f))

(use-fixtures :each session/session-fixture with-clear-and-unprofile)

(deftest toggle-profile-test
  (testing "profile toggling"
    (is+ {:value ["profiled"] :status #{"done"}}
         (session/message {:op  "cider/profile-toggle-var"
                           :ns  "clojure.core"
                           :sym "zipmap"}))
    (is+ {:value ["unprofiled"] :status #{"done"}}
         (session/message {:op  "cider/profile-toggle-var"
                           :ns  "clojure.core"
                           :sym "zipmap"}))))

(deftest profile-summary-test
  (testing "Var profile sumary"
    (session/message {:op  "cider/profile-toggle-var"
                      :ns  "clojure.core"
                      :sym "zipmap"})
    (is (zipmap [:a :b :c] [1 2 3]))
    (is+ {:status #{"done"}
          :value [(mc/via read-string
                          (mc/prefix ["Class: " [:value "clojure.lang.ArraySeq" 0] [:newline]
                                      "Count: " "1" [:newline] [:newline]
                                      "--- Contents:" [:newline] [:newline]]))]}
         (session/message {:op "cider/profile-summary"}))))

(deftest toggle-profile-ns-test
  (testing "toggling profile ns"
    (is+ {:value ["profiled"] :status #{"done"}}
         (session/message {:op  "cider/profile-toggle-ns"
                           :ns  "clojure.string"}))
    (is+ {:value ["unprofiled"] :status #{"done"}}
         (session/message {:op  "cider/profile-toggle-ns"
                           :ns  "clojure.string"}))))
