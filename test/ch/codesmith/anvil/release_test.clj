(ns ch.codesmith.anvil.release-test
  (:require [ch.codesmith.anvil.release :as rel]
            [clojure.test :refer [deftest is]]
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

(defn test-update-readme [{:keys [before after deps/manifest]}]
  (let [readme-test-file (io/file test-dir "README.md")]
    (io/make-parents readme-test-file)
    (spit readme-test-file before)
    (rel/update-readme {:file          readme-test-file
                        :deps-coords   'io.github.codesmith-gmbh/anvil
                        :tag           "v0.1.35"
                        :version       "0.1.35"
                        :deps/manifest manifest})
    (is (= after (slurp readme-test-file)))))

(deftest update-readme-correctness
  (test-update-readme {:before        "# README
```
io.github.codesmith-gmbh/anvil {:git/tag \"????\" :git/sha \"???\"}
```
"
                       :after         "# README
```
io.github.codesmith-gmbh/anvil {:git/tag \"v0.1.35\" :git/sha \"de11727\"}
```
"
                       :deps/manifest :deps})
  (test-update-readme {:before        "# README
```
io.github.codesmith-gmbh/anvil {:mvn/version \"????\"}
```
"
                       :after         "# README
```
io.github.codesmith-gmbh/anvil {:mvn/version \"0.1.35\"}
```
"
                       :deps/manifest :mvn}))
