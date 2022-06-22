(ns outdated
  (:require [babashka.fs :as fs]
            [babashka.process :as ps]
            [clojure.java.io :as io]))

(def deps '{:deps {com.github.liquidz/antq                                  {:mvn/version "1.7.804"}
                   org.clojure/tools.deps.alpha                             {:mvn/version "0.14.1212"}
                   org.apache.maven.resolver/maven-resolver-impl            {:mvn/version "1.8.1"}
                   org.apache.maven.resolver/maven-resolver-connector-basic {:mvn/version "1.8.1"}
                   org.apache.maven.resolver/maven-resolver-transport-file  {:mvn/version "1.8.1"}
                   org.apache.maven.resolver/maven-resolver-transport-http  {:mvn/version "1.8.1"}
                   org.slf4j/slf4j-simple                                   {:mvn/version "1.7.36"}}})

(def exclusions
  [])

(defn tmp-dir-for-deps-map [deps-map-var]
  (let [deps-map-sym (symbol deps-map-var)
        deps-file    (io/file "target"
                              (namespace deps-map-sym)
                              (name deps-map-sym)
                              "deps.edn")]
    (io/make-parents deps-file)
    (spit deps-file (prn-str @deps-map-var))
    (.getParent deps-file)))

(defn check-outdated-deps [dir args]
  (println "Checking outdated dependencies for" (str dir))
  @(ps/process ["clojure"
                "-Sdeps" (with-out-str (pr deps))
                "-M" "-m" "antq.core" (into args
                                            (map #(str "--exclude=" %)
                                                 exclusions))]
               {:inherit true
                :dir     (fs/file dir)})
  (println))