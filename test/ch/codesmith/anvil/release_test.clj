(ns ch.codesmith.anvil.release-test
  (:require [ch.codesmith.anvil.release :as rel]
            [clojure.test :refer :all]
            [clojure.java.io :as io]))

(def test-dir (io/file "target" "test" "release-test"))

(def changelog-before
  "# CHANGELOG

## Unreleased
Hello
")

(def changelog-after
  "# CHANGELOG

## Unreleased

## 1.0.0
Hello
")

(deftest update-changelog-correctness
  (let [change-log-test-file (io/file test-dir "CHANGELOG.md")]
    (io/make-parents change-log-test-file)
    (spit change-log-test-file changelog-before)
    (rel/update-changelog-file change-log-test-file "1.0.0")
    (is (= changelog-after (slurp change-log-test-file)))))

(def readme-before
  "# README
```
io.github.codesmith-gmbh/anvil {:git/tag \"????\" :git/sha \"???\"}
```
")

(def readme-after
  "# README
```
io.github.codesmith-gmbh/anvil {:git/tag \"v0.1.35\" :git/sha \"de11727\"}
```
")

(deftest update-readme-correctness
  (let [readme-test-file (io/file test-dir "README.md")]
    (io/make-parents readme-test-file)
    (spit readme-test-file readme-before)
    (rel/update-readme readme-test-file 'io.github.codesmith-gmbh/anvil "v0.1.35")
    (is (= readme-after (slurp readme-test-file)))))
