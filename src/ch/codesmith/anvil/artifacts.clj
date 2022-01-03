(ns ch.codesmith.anvil.artifacts
  (:require [badigeon.classpath :as classpath]
            [badigeon.clean :as clean]
            [badigeon.compile :as compile]
            [badigeon.bundle :as bundle]
            [integrant.core :as ig]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs])
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

(defn assert-not-nil [value & {:keys [for]}]
  (when-not value
    (throw (ex-info (str for " is required") {:key for})))
  value)

(defmethod ig/init-key ::main-namespace
  [_ main-namespace]
  (assert-not-nil main-namespace :for ::main-namespace))

(defmethod ig/init-key ::target-path
  [_ target-path-override]
  (or target-path-override (fs/path "target")))

(defmethod ig/init-key ::docker-registry
  [_ docker-registry]
  docker-registry)

(defmethod ig/init-key ::lib-name
  [_ lib-name]
  (assert-not-nil lib-name :for ::lib-name))

(defmethod ig/init-key ::version
  [_ version]
  (assert-not-nil version :for ::version))

(defmethod ig/init-key ::java-version
  [_ java-version]
  (assert-not-nil java-version :for ::java-version))

(defmethod ig/init-key ::docker-base-image
  [_ docker-base-image]
  docker-base-image)

(defmethod ig/init-key ::aliases
  [_ aliases]
  (or aliases []))

(defmethod ig/init-key ::basis-opts
  [_ basis-opts]
  basis-opts)

(def aot-config {::aot {:main-namespace (ig/ref ::main-namespace)
                        :aliases        (ig/ref ::aliases)}})

(defmethod ig/init-key ::aot
  [_ {:keys [main-namespace aliases]}]
  (println (str "AOT compile namespace " main-namespace))
  (compile/compile main-namespace {:classpath (classpath/make-classpath {:aliases aliases})})
  :aot-done)


(defn bundle-config [with-aot?]
  (let [with-aot?-merge (if with-aot?
                          #(merge % {:with-aot? (ig/ref ::aot)})
                          identity)]
    {::bundle-out-path   {:lib-name    (ig/ref ::lib-name)
                          :version     (ig/ref ::version)
                          :target-path (ig/ref ::target-path)}
     ::version-file      {:version         (ig/ref ::version)
                          :bundle-out-path (ig/ref ::bundle-out-path)}
     ::bundle            (with-aot?-merge {:out-path     (ig/ref ::bundle-out-path)
                                           :aliases      (ig/ref ::aliases)
                                           :version-file (ig/ref ::version-file)
                                           :basis-opts   (ig/ref ::basis-opts)})
     ::bundle-run-script (with-aot?-merge {:out-path       (ig/ref ::bundle-out-path)
                                           :main-namespace (ig/ref ::main-namespace)})}))

(defmethod ig/init-key ::bundle-out-path
  [_ {:keys [target-path lib-name version]}]
  (let [out-path (fs/path
                   target-path
                   (fs/relativize
                     (fs/absolutize (fs/path "target"))
                     (fs/path (bundle/make-out-path lib-name version))))]
    (fs/create-dirs out-path)
    (str out-path)))

(defmethod ig/init-key ::version-file
  [_ {:keys [version bundle-out-path]}]
  (spit (io/file bundle-out-path "version.edn")
        {:version version}))

(defmethod ig/init-key ::bundle
  [_ {:keys [out-path with-aot? aliases basis-opts]}]
  (println "Bundling the src and the libs")
  (let [deps (b/create-basis basis-opts)
        deps (assoc-in deps
                       [:aliases ::classes :extra-paths]
                       ["target/classes"])]
    (bundle/bundle out-path
                   (if with-aot?
                     {:deps-map deps
                      :aliases  (conj aliases ::classes)}
                     {:aliases aliases}))))

(defn make-executable [f]
  (fs/set-posix-file-permissions f "rwxr-xr-x"))

(defmethod ig/init-key ::bundle-run-script
  [_ {:keys [out-path main-namespace]}]
  (let [bin-dir-path (fs/path out-path "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
          (str
            "#!/bin/bash
DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"
java ${JAVA_OPTS} -cp \"${DIR}/..:${DIR}/../lib/*\" "
            main-namespace
            "\n"))
    (make-executable script-path)))

(def docker-config
  {::java-docker-base-image {:java-version      (ig/ref ::java-version)
                             :docker-base-image (ig/ref ::docker-base-image)}
   ::dockerfile             {:java-docker-base-image (ig/ref ::java-docker-base-image)
                             :java-version           (ig/ref ::java-version)
                             :version                (ig/ref ::version)
                             :target-path            (ig/ref ::target-path)
                             :bundle-out-path        (ig/ref ::bundle-out-path)}
   ::dockerignore-file      {:target-path (ig/ref ::target-path)}
   ::docker-build-script    {:target-path     (ig/ref ::target-path)
                             :docker-registry (ig/ref ::docker-registry)
                             :lib-name        (ig/ref ::lib-name)
                             :version         (ig/ref ::version)}
   ::docker-push-script     {:target-path     (ig/ref ::target-path)
                             :docker-registry (ig/ref ::docker-registry)
                             :lib-name        (ig/ref ::lib-name)
                             :version         (ig/ref ::version)}})

(def java-docker-base-images
  {:openjdk/jre8  "openjdk:8u302-jre-slim"
   :openjdk/jre11 "openjdk:11.0.12-jre-slim"
   :openjdk/jdk14 "openjdk:14.0.2-slim"
   :openjdk/jdk15 "openjdk:15.0.2-slim"
   :openjdk/jdk16 "openjdk:16.0.2-slim"
   :openjdk/jdk17 "openjdk:17.0.1-slim-buster"
   })

(defmethod ig/init-key ::java-docker-base-image
  [_ {:keys [java-version docker-base-image]}]
  (or docker-base-image
      (get java-docker-base-images java-version)))

(defmulti default-java-opts identity)

(defmethod default-java-opts
  :openjdk/jdk17
  [_]
  "-XX:MaxRAMPercentage=85 -XX:+UseZGC")

(defmethod default-java-opts
  :default
  [_]
  "-XX:MaxRAMPercentage=85")

(defmethod ig/init-key ::dockerfile
  [_ {:keys [target-path java-version java-docker-base-image version bundle-out-path]}]
  (println "Creating the Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " java-docker-base-image "\n"
             "ENV VERSION=\"" version "\"\n"
             "ENV LOCATION=\":docker\"\n"
             "ENV JAVA_OPTS=\"" (default-java-opts java-version) "\"\n"
             "COPY " (fs/relativize target-path bundle-out-path) " /app/\n"
             "CMD [\"/app/bin/run.sh\"]\n")))

(defmethod ig/init-key ::dockerignore-file
  [_ {:keys [target-path]}]
  (spit (fs/path target-path ".dockerignore") "classes"))

(defn tag [docker-registry lib-name version]
  (let [tag-base (str (if docker-registry (str docker-registry "/") "") lib-name ":")]
    (str tag-base version)))

(defn generate-docker-script [{:keys [target-path lib-name version docker-registry]} script-name body-fn]
  (let [script-file (fs/path target-path script-name)]
    (spit script-file
          (str "#!/bin/sh\n"
               "dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
               "(
  cd \"$dir\" || exit\n"
               "  " (body-fn (tag docker-registry lib-name version))
               "\n)\n"))
    (make-executable script-file)))

(defmethod ig/init-key ::docker-build-script
  [_ config]
  (generate-docker-script
    config
    "docker-build.sh"
    (fn [version-tag]
      (str "docker build -t " version-tag " ."))))

(defmethod ig/init-key ::docker-push-script
  [_ config]
  (generate-docker-script
    config
    "docker-push.sh"
    (fn [version-tag]
      (str "docker push " version-tag))))

(defn clean [target-path]
  (println "Clean target directory")
  (clean/clean (str target-path))
  (fs/create-dirs target-path))

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
                              ::basis-opts        basis-opts})
        {:keys [::target-path]} (ig/init configuration [::target-path])]
    (clean target-path)
    (ig/init configuration)))
