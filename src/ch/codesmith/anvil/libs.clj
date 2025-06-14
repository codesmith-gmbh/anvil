(ns ch.codesmith.anvil.libs
  (:require [babashka.fs :as fs]
            [ch.codesmith.anvil.core :as ac]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.pom :as pom]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as deploy]
            [taoensso.telemere :as t]))

(defn spit-version-file [{:keys [version dir]}]
  (let [file (b/resolve-path (io/file dir "version.edn"))]
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

(def default-compile-opts
  {:elide-meta     [:doc :file :line :since]
   :direct-linking false                                    ;; fix specter compilation problem before setting true
   })

(defn jar ^String [{:keys [lib
                           version
                           with-pom?
                           polylibs
                           description-data
                           clean?
                           aot
                           basis]
                    :or   {basis (b/create-basis)}}]
  (t/log! {:data {:lib     lib
                  :version version}}
    "build library")
  (let [target-dir (ac/target-dir)
        basis      (if polylibs
                     (patch-basis-for-publication basis polylibs version)
                     basis)
        class-dir  (str (io/file target-dir "classes"))
        ;; own src dirs
        src-dirs   (into []
                     (keep (fn [[lib {:keys [path-key]}]]
                             (and path-key (name lib))))
                     (:classpath basis))
        jar-file   (str (io/file target-dir (str (name lib) "-" version ".jar")))]
    (when clean?
      (ac/clean-target-dir))
    (when with-pom?
      (pom/write-pom {:class-dir        class-dir
                      :lib              lib
                      :version          version
                      :basis            basis
                      :description-data description-data}))
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})
    (when aot
      (t/log! {:level :debug
               :aot   aot}
        "aot compiling")
      (b/compile-clj (merge {:basis        basis
                             :class-dir    class-dir
                             :src-dirs     src-dirs
                             :compile-opts default-compile-opts}
                       aot)))
    (spit-version-file {:version version
                        :dir     (io/file class-dir
                                   (lib-resources-dir lib))})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file
            :manifest  {"Implementation-Version" version}})
    {:jar-file jar-file}))

(defn deploy [{:keys [target-dir jar-file pom-file lib
                      installer sign-releases?]
               :or   {installer      :remote
                      sign-releases? true}}]
  (let [class-dir (io/file target-dir "classes")
        pom-file  (str (or pom-file
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