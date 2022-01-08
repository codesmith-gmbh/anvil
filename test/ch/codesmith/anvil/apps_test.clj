(ns ch.codesmith.anvil.apps-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.apps :as apps]))

(deftest nondir-full-name-correctness
  (is (= "a" (apps/nondir-full-name "a")))
  (is (= "a--b" (apps/nondir-full-name 'a/b)))
  (is (= "a--b--c" (apps/nondir-full-name :a/b "c"))))

(deftest full-jar-file-name-correctness
  (is (= "a--b--1.2.jar" (apps/full-jar-file-name 'a/b "1.2"))))