(ns cider.nrepl.middleware.content-type-test
  (:require
   [cider.nrepl.middleware.content-type :as content-type]
   [cider.nrepl.test-session :as session]
   [clojure.string :as str]
   [clojure.test :refer :all]))

(use-fixtures :each session/session-fixture)

(defmethod content-type/content-type-response :graphviz [{:keys [value] :as response}]
  (let [{:keys [name edges]} value]
    (assoc response
           :content-type ["text/vnd.graphviz" {}]
           :body
           (str "graph " name " {\n"
                (str/join "\n"
                          (for [[from to] edges]
                            (str from " -- " to ";")))
                "\n}"))))

(deftest content-type-middleware-test
  (testing "java.net.URI"
    (is (= {:body ""
            :content-type ["message/external-body"
                           {:URL "https://lambdaisland.com"
                            :access-type "URL"}]
            :status #{"done"}}
           (select-keys (session/message {:op "eval"
                                          :code "(java.net.URI. \"https://lambdaisland.com\")"
                                          :content-type "true"})
                        [:body :content-type :content-transfer-encoding :status]))))

  (testing "java.net.URL"
    (is (= {:body ""
            :content-type ["message/external-body"
                           {:URL "https://lambdaisland.com"
                            :access-type "URL"}]
            :status #{"done"}}
           (select-keys (session/message {:op "eval"
                                          :code "(java.net.URL. \"https://lambdaisland.com\")"
                                          :content-type "true"})
                        [:body :content-type :content-transfer-encoding :status]))))

  (testing "java.io.File"
    (let [f (java.io.File/createTempFile "foo" ".txt")]
      (is (= {:body ""
              :content-type
              ["message/external-body"
               {:URL (str "file:" f) :access-type "URL"}]
              :status #{"done"}}
             (-> {:op "eval"
                  :code (str "(java.io.File. " (pr-str (str f)) ")")
                  :content-type "true"}
                 session/message
                 (select-keys [:body :content-type :content-transfer-encoding :status]))))))

  (testing "java.awt.image.RenderedImage"
    (is (= {:body "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR4XmNgAAIAAAUAAQYUdaMAAAAASUVORK5CYII=",
            :content-type ["image/png" {}]
            :content-transfer-encoding "base64"
            :status #{"done"}}
           (-> {:op "eval"
                :code "(java.awt.image.BufferedImage. 1 1 java.awt.image.BufferedImage/TYPE_INT_ARGB)"
                :content-type "true"}
               session/message
               (select-keys [:body :content-type :content-transfer-encoding :status])))))

  (testing "custom type implementation"
    (is (= {:body "graph foo {\na -- b;\nb -- c;\n}"
            :content-type ["text/vnd.graphviz" {}]
            :status #{"done"}}
           (-> {:op "eval"
                :code (str "^{:type :graphviz} "
                           (pr-str
                            {:name "foo"
                             :edges [["a" "b"] ["b" "c"]]}))
                :content-type "true"}
               session/message
               (select-keys [:body :content-type :content-transfer-encoding :status]))))))
