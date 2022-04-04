(ns ch.codesmith.anvil.release-test
  (:require [ch.codesmith.anvil.release :as rel]
            [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.time LocalDate)))

(def test-dir (io/file "target" "test" "release-test"))

(def changelog-before
  "# CHANGELOG

## Unreleased
Hello
")

(def changelog-after
  "# CHANGELOG

## Unreleased

## 1.0.0 (2022-01-22)
Hello
")

(deftest update-changelog-correctness
  (let [change-log-test-file (io/file test-dir "CHANGELOG.md")]
    (io/make-parents change-log-test-file)
    (spit change-log-test-file changelog-before)
    (rel/update-changelog-file change-log-test-file {:version    "1.0.0"
                                                     :local-date (LocalDate/of 2022 1 22)})
    (is (= changelog-after (slurp change-log-test-file)))))

(defn test-update-readme [{:keys [before after artifact-type]}]
  (let [update-readme (rel/update-readme {:artifacts       [{:deps-coords   'io.github.codesmith-gmbh/anvil
                                                             :artifact-type artifact-type}]
                                          :docker-registry "image"
                                          :version         "0.1.35"})]
    (is (= (update-readme before) after))))

(deftest update-readme-correctness
  (test-update-readme {:before        "# README
```deps
io.github.codesmith-gmbh/anvil {:git/tag \"???\" :git/sha \"???\"}
```
"
                       :after         "# README
```deps
io.github.codesmith-gmbh/anvil {:git/tag \"v0.1.35\" :git/sha \"de11727\"}
```
"
                       :artifact-type :deps})
  (test-update-readme {:before        "# README
```deps
io.github.codesmith-gmbh/anvil {:mvn/version \"????\"}
```
"
                       :after         "# README
```deps
io.github.codesmith-gmbh/anvil {:mvn/version \"0.1.35\"}
```
"
                       :artifact-type :mvn})
  (test-update-readme {:before        "# README
```deps
docker pull ???
```
"
                       :after         "# README
```deps
docker pull image/anvil:0.1.35
```
"
                       :artifact-type :docker-image}))
