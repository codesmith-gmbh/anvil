(ns ch.codesmith.anvil.libs
  (:require [babashka.fs :as fs]
            [ch.codesmith.anvil.basis :as ab]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.pom :as pom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deploy]))

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

(defn patch-basis-for-publication-one [{:keys [deps] :as basis} polylib version]
  (if (contains? deps polylib)
    (-> basis
      (assoc-in [:deps polylib] {:mvn/version version})
      (update-in [:libs polylib] (fn [lib]
                                   (assoc
                                     lib
                                     :deps/manifest :mvn
                                     :mvn/version version))))
    basis))

(defn patch-basis-for-publication [basis polylibs version]
  (reduce #(patch-basis-for-publication-one %1 %2 version)
    basis
    polylibs))

(defn jar ^String [{:keys [lib
                           version
                           basis
                           with-pom?
                           polylibs
                           root
                           target-dir
                           description-data
                           clean?
                           aot]
                    :or   {root "."}}]
  (let [root (fs/absolutize root)]
    (binding [b/*project-root* (str root)]
      (let [target-dir (or target-dir (str (fs/path root "target")))
            basis      (or basis (ab/create-basis {}))
            basis      (if polylibs
                         (patch-basis-for-publication basis polylibs version)
                         basis)
            class-dir  (str (io/file target-dir "classes"))
            ;; own src dirs
            src-dirs   (into []
                         (keep (fn [[lib {:keys [path-key]}]]
                                 (and path-key (str (fs/path root (name lib))))))
                         (:classpath basis))
            ;; local-root dirs
            src-dirs   (into src-dirs
                         (comp
                           (map second)
                           (filter :local/root)
                           (mapcat :paths))
                         (:libs basis))
            jar-file   (str (io/file target-dir (str (name lib) "-" version ".jar")))]
        (when clean?
          (b/delete {:path (str target-dir)}))
        (when with-pom?
          (pom/write-pom {:class-dir        class-dir
                          :lib              lib
                          :version          version
                          :basis            basis
                          :description-data description-data}))
        (b/copy-dir {:src-dirs   src-dirs
                     :target-dir class-dir})
        (when aot
          (b/compile-clj (merge {:basis     basis
                                 :class-dir (str (fs/absolutize class-dir))
                                 :src-dirs  (into []
                                              (map (comp str fs/absolutize))
                                              src-dirs)}
                           aot)))
        (spit-version-file {:version version
                            :dir     (io/file class-dir
                                       (lib-resources-dir lib))})
        (b/jar {:class-dir class-dir
                :jar-file  jar-file
                :manifest  {"Implementation-Version" version}})
        jar-file))))

(defn deploy [{:keys [jar-file pom-file root-dir target-dir class-dir lib
                      installer sign-releases?]
               :or   {root-dir       "."
                      installer      :remote
                      sign-releases? true}}]
  (let [target-dir (or target-dir (str (fs/path root-dir "target")))
        class-dir  (or class-dir (fs/path target-dir "classes"))
        pom-file   (str (or pom-file
                          (fs/path class-dir
                            "META-INF"
                            "maven"
                            (namespace lib)
                            (name lib)
                            "pom.xml")))]
    (deploy/deploy {:artifact        jar-file
                    :installer       installer
                    :pom-file        pom-file
                    :sign-releases?  sign-releases?
                    :gpg-batch-mode? true
                    :target-dir      target-dir})))