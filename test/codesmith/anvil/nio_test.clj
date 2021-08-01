(ns codesmith.anvil.nio-test
  (:require [clojure.test :refer :all]
            [codesmith.anvil.nio :as nio]))

(deftest path-correctness
  (is (= "parent" (str (nio/path "parent"))))

  (is (= "parent/child" (str (nio/path "parent" "child"))))
  (is (= "parent/child" (str (nio/path (nio/as-path "parent") "child"))))

  (is (= "parent/child/a/b" (str (nio/path "parent" "child" "a" "b"))))
  (is (= "parent/child/a/b" (str (nio/path (nio/as-path "parent") "child" "a" "b")))))