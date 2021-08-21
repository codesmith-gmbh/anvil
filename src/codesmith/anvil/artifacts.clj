(ns codesmith.anvil.artifacts
  (:require [badigeon.classpath :as classpath]
            [badigeon.clean :as clean]
            [badigeon.compile :as compile]
            [badigeon.bundle :as bundle]
            [clojure.edn :as edn]
            [codesmith.anvil.nio :as nio]
            [integrant.core :as ig])
  (:import (java.nio.file Files)))

(defn assert-not-nil [value & {:keys [for]}]
  (when-not value
    (throw (ex-info (str for " is required") {:key for})))
  value)

(defmethod ig/init-key ::main-namespace
  [_ main-namespace]
  (assert-not-nil main-namespace :for ::main-namespace))

(defmethod ig/init-key ::target-path
  [_ target-path-override]
  (or target-path-override (nio/path "target")))

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

(defmethod ig/init-key ::aliases
  [_ aliases]
  (or aliases []))

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
     ::bundle            (with-aot?-merge {:out-path (ig/ref ::bundle-out-path)
                                           :aliases  (ig/ref ::aliases)})
     ::bundle-run-script (with-aot?-merge {:out-path       (ig/ref ::bundle-out-path)
                                           :main-namespace (ig/ref ::main-namespace)})}))

(defmethod ig/init-key ::bundle-out-path
  [_ {:keys [target-path lib-name version]}]
  (str
    (nio/resolve
      target-path
      (nio/relativize
        (nio/absolute-path (nio/path "target"))
        (nio/path (bundle/make-out-path lib-name version))))))

(defmethod ig/init-key ::bundle
  [_ {:keys [out-path with-aot? aliases]}]
  (println "Bundling the src and the libs")
  (bundle/bundle out-path
                 (if with-aot?
                   {:deps-map (assoc-in (edn/read-string (slurp "deps.edn"))
                                        [:aliases ::classes :extra-paths]
                                        ["target/classes"])
                    :aliases  (conj aliases ::classes)}
                   {:aliases aliases})))

(defmethod ig/init-key ::bundle-run-script
  [_ {:keys [out-path main-namespace]}]
  (let [bin-dir-path (nio/resolve out-path "bin")
        script-path  (nio/resolve bin-dir-path "run.sh")]
    (nio/ensure-directory bin-dir-path)
    (spit script-path
          (str
            "#!/bin/bash
DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"
java ${JAVA_OPTS} -cp \"${DIR}/..:${DIR}/../lib/*\" "
            main-namespace
            "\n"))
    (nio/make-executable script-path)))

(def docker-config
  {::java-docker-base-image {:java-version (ig/ref ::java-version)}
   ::dockerfile             {:java-docker-base-image (ig/ref ::java-docker-base-image)
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
  {:openjdk/jre8  "opendjk:8u302-jre-slim"
   :openjdk/jre11 "openjdk:11.0.12-jre-slim"
   :openjdk/jdk14 "opendjk:14.0.2-slim"
   :openjdk/jdk15 "opendjk:15.0.2-slim"
   :openjdk/jdk16 "opendjk:16.0.2-slim"
   })

(defmethod ig/init-key ::java-docker-base-image
  [_ {:keys [java-version]}]
  (get java-docker-base-images java-version))

(defmethod ig/init-key ::dockerfile
  [_ {:keys [target-path java-docker-base-image version bundle-out-path]}]
  (println "Creating the Dockerfile")
  (spit (nio/path target-path "Dockerfile")
        (str "FROM " java-docker-base-image "\n"
             "ENV VERSION=\"" version "\"\n"
             "ENV LOCATION=\":docker\"\n"
             "COPY " (nio/relativize target-path bundle-out-path) " /app/\n"
             "CMD [\"/app/bin/run.sh\"]\n")))

(defmethod ig/init-key ::dockerignore-file
  [_ {:keys [target-path]}]
  (spit (nio/path target-path ".dockerignore") "classes"))

(defn tag [docker-registry lib-name version]
  (let [tag-base (str (if docker-registry (str docker-registry "/") "") lib-name ":")]
    (str tag-base version)))

(defn generate-docker-script [{:keys [target-path lib-name version docker-registry]} script-name body-fn]
  (let [script-file (nio/path target-path script-name)]
    (spit script-file
          (str "#!/bin/sh\n"
               "dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
               "(
  cd \"$dir\" || exit\n"
               "  " (body-fn (tag docker-registry lib-name version))
               "\n)\n"))
    (nio/make-executable script-file)))

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
  (nio/ensure-directory target-path))

(defn make-docker-artifact [{:keys [main-namespace docker-registry lib-name version java-version
                                    aliases aot? target-path] :or {aliases [] aot? true}}]
  (let [configuration (merge (if aot? aot-config {})
                             (bundle-config aot?)
                             docker-config
                             {::main-namespace  main-namespace
                              ::target-path     target-path
                              ::docker-registry docker-registry
                              ::lib-name        lib-name
                              ::version         version
                              ::java-version    java-version
                              ::aliases         aliases})
        {:keys [::target-path]} (ig/init configuration [::target-path])]
    (clean target-path)
    (ig/init configuration)))

(comment
  (make-docker-artifact {:main-namespace 'codesmith.anvil.artifacts
                         :lib-name       'anvic
                         :aliases        []
                         :version        "1.0.0"
                         :java-version   :openjdk/jre11
                         :aot?           false})
  )