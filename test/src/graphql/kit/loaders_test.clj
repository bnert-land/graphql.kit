(ns graphql.kit.loaders-test
  (:require
    [clojure.test :refer [are deftest testing is]]
    [graphql.kit.protos.loader :as loader]
    [graphql.kit.loaders.default :as l.default]
    [graphql.kit.loaders.edn :as l.edn]
    [graphql.kit.loaders.aero :as l.aero]))

(deftest aero-loader
  (testing "aero loader"
    (let [l   (l.aero/loader!)
          exp {:ima    "teapot"
               :quotes {:ralph "me fail english... thats umpossible"}}]
      (is (= exp
             (loader/path l "test/resources/graphql/kit/simple-aero.edn")
             (loader/resource l "graphql/kit/simple-aero-resource.edn"))))))

(deftest default-loader
  (testing "default loader"
    (let [l   (l.default/loader!)
          exp "\"I wet my arm pants\"\n"]
      (is (= exp
             (loader/path l "test/resources/graphql/kit/message.txt")
             (loader/resource l "graphql/kit/message.txt"))))))

; you can tell its an aspen by the way it is...
(deftest edn-loader
  (testing "edn loader"
    (let [l   (l.edn/loader!)
          exp {:quote "My parents won't let me use scissors."}]
      (is (= exp
             (loader/path l "test/resources/graphql/kit/message.edn")
             (loader/resource l "graphql/kit/message.edn"))))))

