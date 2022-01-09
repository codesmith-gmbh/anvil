(ns ch.codesmith.anvil.libs-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [ch.codesmith.anvil.helloworld :as hw]
            [clojure.java.io :as io])
  (:import (java.util.zip ZipFile ZipEntry)))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))
(def target-dir "target")

(defn test-jar [with-pom?]
  (let [basis (b/create-basis {:project "deps.edn"
                               :aliases [:test-resources]
                               })]
    (libs/jar {:lib        lib
               :version    version
               :with-pom?  with-pom?
               :root       "."
               :basis      basis
               :target-dir target-dir
               :clean?     true})
    (let [classes-dir (fs/path target-dir "classes")
          jar-file    (fs/path target-dir (str (name lib) "-" version ".jar"))]
      (is (fs/directory? (fs/path classes-dir "ch")))
      (is (fs/regular-file? (fs/path classes-dir "test.txt")))
      (is (fs/regular-file? jar-file))
      (with-open [zip-file (ZipFile. (.toFile jar-file))]
        (let [entries (into #{}
                            (comp (map (fn [^ZipEntry zip-entry]
                                         {:name       (.getName zip-entry)
                                          :directory? (.isDirectory zip-entry)}))
                                  (remove (fn [{:keys [name]}]
                                            (.startsWith name "META-INF"))))
                            (enumeration-seq (.entries zip-file)))]
          (doseq [{:keys [name directory?] :as entry} entries]
            (is ((if directory? fs/directory? fs/regular-file?)
                 (fs/path classes-dir name))
                entry)))))))

(deftest jar-correctness
  (test-jar true)
  (test-jar false))

(deftest deploy-local-correctness
  (let [jar-file    (libs/jar (merge hw/base-properties
                                     {:with-pom? true
                                      :clean?    true}))
        m2-repo-dir (io/file
                      (System/getProperty "user.home")
                      ".m2"
                      "repository"
                      "ch"
                      "codesmith-test"
                      "hello")]
    (b/delete {:path (str m2-repo-dir)})
    (libs/deploy {:jar-file       jar-file
                  :target-dir     hw/target-dir
                  :installer      :local
                  :sign-releases? false
                  :lib            hw/lib})
    (is (fs/directory? m2-repo-dir))))