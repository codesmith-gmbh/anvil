(ns ch.codesmith.anvil.libs
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [deps-deploy.deps-deploy :as deploy]
            [babashka.fs :as fs]
            ch.codesmith.anvil.io
            [ch.codesmith.anvil.shell :as sh]))

(defn jar ^String [{:keys [lib
                           version
                           basis
                           with-pom?
                           root
                           target-dir
                           clean?]}]
  (let [basis     (or basis (b/create-basis {:project (str (io/file root "deps.edn"))}))
        class-dir (str (io/file target-dir "classes"))
        src-dirs  (into []
                        (keep (fn [[lib {:keys [path-key]}]]
                                (and path-key (str (io/file root (name lib))))))
                        (:classpath basis))
        jar-file  (str (io/file target-dir (str (name lib) "-" version ".jar")))]
    (when clean?
      (b/delete {:path (str target-dir)}))
    (when with-pom?
      (b/write-pom {:class-dir class-dir
                    :lib       lib
                    :version   version
                    :basis     basis}))
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file})
    jar-file))

(defn deploy [{:keys [jar-file pom-file target-dir classes-dir lib
                      installer sign-releases?]
               :or   {target-dir     "target"
                      classes-dir    "target/classes"
                      installer      :remote
                      sign-releases? true}}]
  (let [pom-file (str (or pom-file
                          (fs/path classes-dir
                                   "META-INF"
                                   "maven"
                                   (namespace lib)
                                   (name lib)
                                   "pom.xml")))]
    (deploy/deploy {:artifact       jar-file
                    :installer      installer
                    :pom-file       pom-file
                    :sign-releases? sign-releases?
                    :target-dir     target-dir})))