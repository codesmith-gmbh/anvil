(ns ch.codesmith.anvil.libs-test
  (:require [babashka.fs :as fs]
            [ch.codesmith.anvil.helloworld :as hw]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.build.api :as b])
  (:import (java.util.zip ZipEntry ZipFile)))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))
(def target-dir "target")

(defn test-jar [with-pom?]
  (binding [libs/*basis-creation-fn* #(b/create-basis {:project "deps.edn"
                                                       :aliases [:test-resources]})]
    (libs/jar {:lib              lib
               :version          version
               :with-pom?        with-pom?
               :root             "."
               :target-dir       target-dir
               :description-data {:authors [{:name "Stanislas Nanchen"}]
                                  :url     "https://codesmith.ch"}
               :clean?           true})
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
  (test-jar false)
  (test-jar true))

(deftest deploy-local-correctness
  (let [{:keys [jar-file]} (libs/jar (merge hw/base-properties
                                       {:with-pom? true
                                        :clean?    true}))
        m2-repo-dir (io/file
                      (System/getProperty "user.home")
                      ".m2"
                      "repository"
                      "ch"
                      "codesmith-test"
                      "hello")]
    (prn jar-file)
    (b/delete {:path (str m2-repo-dir)})
    (libs/deploy {:jar-file       jar-file
                  :root-dir       hw/root-dir
                  :installer      :local
                  :sign-releases? false
                  :lib            hw/lib})
    (is (fs/directory? m2-repo-dir))))