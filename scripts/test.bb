(ns test
  (:require [babashka.fs :as fs]
            [babashka.process :as ps]))

(def deps '{:aliases {:runner {:extra-deps {lambdaisland/kaocha    {:mvn/version "1.85.1342"}
                                            org.slf4j/slf4j-simple {:mvn/version "2.0.7"}}}}})

(defn run-tests [directory]
  (when (fs/exists? (fs/path directory "tests.edn"))
    (println "Testing " (str directory))
    (->
      (ps/process
        ["clojure" "-Sdeps" (with-out-str (pr deps))
         "-M:test:runner" "-m" "kaocha.runner"]
        {:inherit true
         :dir     (fs/file directory)})
      (ps/check))
    (println)))
