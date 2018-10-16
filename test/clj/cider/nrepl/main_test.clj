(ns cider.nrepl.main-test
  (:require
   [cider.nrepl :refer [wrap-debug cider-middleware]]
   [cider-nrepl.main :as m]
   [clojure.test :refer :all]
   [nrepl.core :as nrepl]
   [nrepl.server :as nrepl.server]
   [nrepl.transport :as transport]))

(defn start-stop-nrepl-session [opts]
  (with-open [server    (#'m/start-nrepl opts)
              transport (nrepl/connect :port (:port server))]
    (transport/send transport {:op "clone" :id 1})
    (let [session-id (:new-session (transport/recv transport 1000))]
      (assert session-id)
      (transport/send transport {:session session-id
                                 :id      2
                                 :op      "clone"})
      (is (= (:status (transport/recv transport 1000)) ["done"])))))

(deftest start-nrepl-test
  (testing "passing a specific handler should work"
    (let [opts {:handler nrepl.server/default-handler}]
      (start-stop-nrepl-session opts)))

  (testing "passing a sequence instead of a map shouldn't crash"
    (let [opts ["cider.nrepl/cider-middleware"]]
      (start-stop-nrepl-session opts)))

  (testing "passing nil shouldn't crash"
    (let [opts nil]
      (start-stop-nrepl-session opts)))

  (testing "passing valid middleware should work"
    (let [opts {:middleware ["cider.nrepl/cider-middleware"]}]
      (start-stop-nrepl-session opts)))

  (testing "passing options as given by boot task middleware should work"
    (let [opts {:middleware '(cider.nrepl.middleware.version/wrap-version
                              cider.nrepl.middleware.apropos/wrap-apropos)
                :port nil
                :bind nil}]
      (start-stop-nrepl-session opts))))
