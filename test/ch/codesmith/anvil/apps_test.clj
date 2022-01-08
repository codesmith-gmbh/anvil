(ns ch.codesmith.anvil.apps-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.apps :as apps]
            [clojure.tools.build.api :as b]))

(deftest nondir-full-name-correctness
  (is (= "a" (apps/nondir-full-name "a")))
  (is (= "a--b" (apps/nondir-full-name 'a/b)))
  (is (= "a--b--c" (apps/nondir-full-name :a/b "c"))))

(deftest full-jar-file-name-correctness
  (is (= "a--b--1.2.jar" (apps/full-jar-file-name 'a/b "1.2"))))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))

(defn test-docker-generator [with-aot?]
  (apps/docker-generator {:lib            lib
                          :version        version
                          :root           "."
                          :java-version   :openjdk/jdk17
                          :target-dir     "target"
                          :main-namespace "test"}))


(deftest docker-generator-correctness
  (test-docker-generator false))