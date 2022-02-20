(ns ch.codesmith.anvil.libs
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [deps-deploy.deps-deploy :as deploy]
            [babashka.fs :as fs]
            [ch.codesmith.anvil.pom :as pom]
            ch.codesmith.anvil.io
            [ch.codesmith.anvil.basis :as ab]
            [clojure.string :as str]))

(defn spit-version-file [{:keys [version dir]}]
  (let [file (io/file dir "version.edn")]
    (io/make-parents file)
    (spit file
          {:version version})))

(defn lib-resources-dir [lib]
  (io/file
    (when-let [ns (namespace lib)]
      (str/replace ns "." "/"))
    (name lib)))

(defn jar ^String [{:keys [lib
                           version
                           basis
                           with-pom?
                           root
                           target-dir
                           description-data
                           clean?
                           aot]}]
  (let [basis     (or basis (ab/create-basis {:project (str (io/file root "deps.edn"))}))
        class-dir (str (io/file target-dir "classes"))
        src-dirs  (into []
                        (keep (fn [[lib {:keys [path-key]}]]
                                (and path-key (str (io/file root (name lib))))))
                        (:classpath basis))
        jar-file  (str (io/file target-dir (str (name lib) "-" version ".jar")))]
    (when clean?
      (b/delete {:path (str target-dir)}))
    (when with-pom?
      (pom/write-pom {:class-dir        class-dir
                      :lib              lib
                      :version          version
                      :basis            basis
                      :description-data description-data}))
    (if aot
      (binding [b/*project-root* (str (fs/absolutize root))]
        (b/compile-clj (merge {:basis     basis
                               :class-dir (str (fs/absolutize class-dir))
                               :src-dirs  (into []
                                                (map (comp str fs/absolutize))
                                                src-dirs)}
                              aot)))
      (b/copy-dir {:src-dirs   src-dirs
                   :target-dir class-dir}))
    (spit-version-file {:version version
                        :dir     (io/file class-dir
                                          (lib-resources-dir lib))})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file})
    jar-file))

(defn deploy [{:keys [jar-file pom-file target-dir class-dir lib
                      installer sign-releases?]
               :or   {target-dir     "target"
                      class-dir      "target/classes"
                      installer      :remote
                      sign-releases? true}}]
  (let [pom-file (str (or pom-file
                          (fs/path class-dir
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