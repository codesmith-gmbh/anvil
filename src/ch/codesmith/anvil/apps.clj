(ns ch.codesmith.anvil.apps
  (:require [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as bc]
            [com.rpl.specter :as sp])
  (:import (java.nio.file Path OpenOption Files StandardOpenOption)))

(extend Path
  io/IOFactory
  (assoc io/default-streams-impl
    :make-input-stream (fn [^Path x opts] (io/make-input-stream
                                            (Files/newInputStream x (make-array OpenOption 0)) opts))
    :make-output-stream (fn [^Path x opts] (io/make-output-stream
                                             (Files/newOutputStream x (if (:append opts)
                                                                        (into-array OpenOption [StandardOpenOption/APPEND])
                                                                        (make-array OpenOption 0))) opts))))

(defn compile-clj [basis class-dir]
  (b/compile-clj {:basis     basis
                  :class-dir class-dir
                  :src-dirs  (into (:paths basis)
                                   (-> basis :resolve-args :extra-paths))}))

(defn spit-version-file [version app-out-path]
  (spit (io/file app-out-path "version.edn")
        {:version version}))

(defmulti copy-jar (fn [lib props jar-dir target-dir]
                     (:deps/manifest props)))

(defmethod copy-jar
  :deps
  [lib props jar-dir target-dir]
  (let [target-dir     (io/file target-dir (libs/nondir-full-name lib))
        jar-file       (libs/jar lib props target-dir)
        copy-file-args {:src    (str jar-file)
                        :target (str (io/file jar-dir (.getName jar-file)))}]
    (b/copy-file copy-file-args)))

(defmethod copy-jar
  :mvn
  [lib props jar-dir target-dir]
  (let [jar-file (io/file (libs/jar-file-name lib (:mvn/version props)))
        path     (sp/select-one! [:paths sp/ALL] props)]
    (b/copy-file {:src    path
                  :target (str (io/file jar-dir (.getName jar-file)))})))

(defn all-libs [{:keys [classpath libs]}]
  (let [used-libs (into #{}
                        (comp (map second)
                              (keep :lib-name))
                        classpath)]
    (into {}
          (map
            (fn [lib]
              [lib (get libs lib)]))
          used-libs)))

(defn copy-jars [basis jar-dir target-dir]
  (let [all-libs (all-libs basis)]
    (doseq [[lib props] all-libs]
      (copy-jar lib props jar-dir target-dir))))

(comment
  (all-libs basis)

  (def basis (b/create-basis {:project "deps.edn"}))

  (copy-jars basis "target/jars" "target/libs")

  )

(defn make-executable [f]
  (fs/set-posix-file-permissions f "rwxr-xr-x"))

(defn bundle-run-script [{:keys [out-path main-namespace]}]
  (let [bin-dir-path (fs/path out-path "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
          (str
            "#!/bin/bash
DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"
java ${JAVA_OPTS} -cp \"${DIR}/..:/lib/*\" "
            main-namespace
            "\n"))
    (make-executable script-path)))

(def java-docker-base-images
  {:openjdk/jre8  "openjdk:8u302-jre-slim"
   :openjdk/jre11 "openjdk:11.0.12-jre-slim"
   :openjdk/jdk14 "openjdk:14.0.2-slim"
   :openjdk/jdk15 "openjdk:15.0.2-slim"
   :openjdk/jdk16 "openjdk:16.0.2-slim"
   :openjdk/jdk17 "openjdk:17.0.1-slim-buster"})


(defmulti default-java-opts identity)

(defmethod default-java-opts
  :openjdk/jdk17
  [_]
  "-XX:MaxRAMPercentage=85 -XX:+UseZGC")

(defmethod default-java-opts
  :default
  [_]
  "-XX:MaxRAMPercentage=85")

(defn app-dockerfile [{:keys [target-path java-version docker-base-image-name version bundle-out-path]}]
  (println "Creating the App Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " docker-base-image-name "\n"
             "ENV VERSION=\"" version "\"\n"
             "ENV LOCATION=\":docker\"\n"
             "ENV JAVA_OPTS=\"" (default-java-opts java-version) "\"\n"
             "COPY " (fs/relativize target-path bundle-out-path) " /app/\n"
             "CMD [\"/app/bin/run.sh\"]\n")))

(defn lib-dockerfile [{:keys [target-path bundle-out-path java-docker-base-image]}]
  (println "Creating the Lib Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " java-docker-base-image "\n"
             "COPY " (fs/relativize target-path bundle-out-path) " /lib/\n")))


(defn spit-dockerignore-file [target-path]
  (spit (fs/path target-path ".dockerignore") "classes"))

(defn tag-base [docker-registry lib-name]
  (str (if docker-registry (str docker-registry "/") "") lib-name ":"))

(defn app-docker-tag [tag-base version]
  (str tag-base version))

(defn lib-docker-tag [tag-base {:keys [libs]}]
  (let [serialized-libs (pr-str
                          (into []
                                (map (fn [[lib coords]]
                                       [lib (dissoc coords :dependents :parents :paths :exclusions
                                                    :deps/root)]))
                                libs))
        hash            (bc/bytes->hex (hash/sha3-256 serialized-libs))]
    (str tag-base "lib-" hash)))

(defn generate-docker-script [{:keys [target-path tag-base version]} script-name body-fn]
  (let [script-file (fs/path target-path script-name)]
    (spit script-file
          (str "#!/bin/sh\n"
               "dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
               "(
  cd \"$dir\" || exit\n"
               "  " (body-fn (app-docker-tag tag-base version))
               "\n)\n"))
    (make-executable script-file)))

(defn generate-docker-build-script [_ config]
  (generate-docker-script
    config
    "docker-build.sh"
    (fn [version-tag]
      (str "docker build -t " version-tag " ."))))

(defn generate-docker-push-script [_ config]
  (generate-docker-script
    config
    "docker-push.sh"
    (fn [version-tag]
      (str "docker push " version-tag))))

(defn clean [target-path]
  (println "Clean target directory")
  (b/delete {:path (str target-path)})
  (fs/create-dirs target-path))

(defn generate-docker-artifact-scripts [{:keys [main-namespace docker-registry lib-name version java-version
                                                docker-base-image
                                                aliases basis-opts aot? target-path] :or {aliases [] aot? true}}]
  (let []))

(def aot-config {::aot {:main-namespace ::main-namespace
                        :aliases        ::aliases}})

(defn bundle-config [with-aot?]
  (let [with-aot?-merge (if with-aot?
                          #(merge % {:with-aot? ::aot})
                          identity)]
    {::bundle-out-path   {:lib-name    ::lib-name
                          :version     ::version
                          :target-path ::target-path}
     ::version-file      {:version         ::version
                          :bundle-out-path ::bundle-out-path}
     ::bundle            (with-aot?-merge {:out-path     ::bundle-out-path
                                           :aliases      ::aliases
                                           :version-file ::version-file
                                           :basis-opts   ::basis-opts})
     ::bundle-run-script (with-aot?-merge {:out-path       ::bundle-out-path
                                           :main-namespace ::main-namespace})}))

(def docker-config
  {::java-docker-base-image {:java-version      ::java-version
                             :docker-base-image ::docker-base-image}
   ::dockerfile             {:java-docker-base-image ::java-docker-base-image
                             :java-version           ::java-version
                             :version                ::version
                             :target-path            ::target-path
                             :bundle-out-path        ::bundle-out-path}
   ::dockerignore-file      {:target-path ::target-path}
   ::docker-build-script    {:target-path     ::target-path
                             :docker-registry ::docker-registry
                             :lib-name        ::lib-name
                             :version         ::version}
   ::docker-push-script     {:target-path     ::target-path
                             :docker-registry ::docker-registry
                             :lib-name        ::lib-name
                             :version         ::version}})


(defn make-docker-artifact [{:keys [main-namespace docker-registry lib-name version java-version
                                    docker-base-image
                                    aliases basis-opts aot? target-path] :or {aliases [] aot? true}}]
  (let [configuration (merge (if aot? aot-config {})
                             (bundle-config aot?)
                             docker-config
                             {::main-namespace    main-namespace
                              ::target-path       target-path
                              ::docker-registry   docker-registry
                              ::lib-name          lib-name
                              ::version           version
                              ::java-version      java-version
                              ::aliases           aliases
                              ::docker-base-image docker-base-image
                              ::basis-opts        basis-opts})]
    (clean target-path)
    ;do it
    ))
