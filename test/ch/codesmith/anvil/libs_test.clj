(ns ch.codesmith.anvil.libs-test
  (:require [babashka.fs :as fs]
            [ch.codesmith.anvil.core :as ac]
            [ch.codesmith.anvil.helloworld :as hw]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.tools.build.api :as b])
  (:import (java.util.zip ZipEntry ZipFile)))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))

(defn test-jar [with-pom?]
  (hw/with-root-dir
    (libs/jar {:lib              lib
               :basis            (b/create-basis {:project "deps.edn"
                                                  :aliases [:test-resources]})
               :version          version
               :with-pom?        with-pom?
               :description-data {:authors [{:name "Stanislas Nanchen"}]
                                  :url     "https://codesmith.ch"}
               :clean?           true})
    (let [target      (b/resolve-path (ac/target-dir))
          classes-dir (fs/path target "classes")
          jar-file    (fs/path target (str (name lib) "-" version ".jar"))]
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
  (hw/with-root-dir
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
      (libs/deploy {:jar-file       (b/resolve-path jar-file)
                    :target-dir     (b/resolve-path (ac/target-dir))
                    :installer      :local
                    :sign-releases? false
                    :lib            hw/lib})
      (is (fs/directory? m2-repo-dir)))))