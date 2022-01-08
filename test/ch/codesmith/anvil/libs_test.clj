(ns ch.codesmith.anvil.libs-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.libs :as libs]))

(deftest nondir-full-name-correctness
  (is (= "a" (libs/nondir-full-name "a")))
  (is (= "a--b" (libs/nondir-full-name 'a/b)))
  (is (= "a--b--c") (libs/nondir-full-name :a/b "c")))

(deftest jar-file-name-correctness
  (is (= "a--b--1.2.jar" (libs/jar-file-name 'a/b "1.2"))))