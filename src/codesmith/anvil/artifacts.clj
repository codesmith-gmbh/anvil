(ns codesmith.anvil.artifacts
  (:require [badigeon.clean :as clean]
            [badigeon.compile :as compile]
            [badigeon.bundle :as bundle]
            [clojure.edn :as edn]
            [codesmith.anvil.nio :as nio]
            [integrant.core :as ig]))

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

(def aot-config {::aot {:main-namespace (ig/ref ::main-namespace)}})

(defmethod ig/init-key ::aot
  [_ {:keys [main-namespace]}]
  (println (str "AOT compile namespace " main-namespace))
  (compile/compile main-namespace)
  :aot-done)


(defn bundle-config [with-aot?]
  (let [with-aot?-merge (if with-aot?
                          #(merge % {:with-aot? (ig/ref ::aot)})
                          identity)]
    {::bundle-out-path   {:lib-name (ig/ref ::lib-name)
                          :version  (ig/ref ::version)}
     ::bundle            (with-aot?-merge {:out-path (ig/ref ::bundle-out-path)})
     ::bundle-run-script (with-aot?-merge {:out-path       (ig/ref ::bundle-out-path)
                                           :main-namespace (ig/ref ::main-namespace)})}))

(defmethod ig/init-key ::bundle-out-path
  [_ {:keys [lib-name version]}]
  (bundle/make-out-path lib-name version))

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
                             :version         (ig/ref ::version)}})

(def java-docker-base-images
  {:openjdk/jre8  "opendjk:8u265-jre-slim"
   :openjdk/jre11 "openjdk:11.0.8-jre-slim"
   :openjdk/jdk14 "opendjk:14.0.2-slim"})

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
             "COPY " (nio/relativize (nio/absolute-path target-path) bundle-out-path) " /opt/app/\n"
             "CMD [\"/opt/app/bin/run.sh\"]\n")))

(defmethod ig/init-key ::dockerignore-file
  [_ {:keys [target-path]}]
  (spit (nio/path target-path ".dockerignore") "classes"))

(defmethod ig/init-key ::docker-build-script
  [_ {:keys [target-path lib-name version docker-registry]}]
  (let [script-file (nio/path target-path "docker-build.sh")]
    (spit script-file
          (str "#!/bin/bash\n"
               "DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"\n"
               "(
  cd \"$DIR\" || exit
  docker build -t " (if docker-registry (str docker-registry "/") "") lib-name ":" version " .
)\n"))
    (nio/make-executable script-file)))


(defn clean [target-path]
  (println "Clean target directory")
  (clean/clean (str target-path)))


(defn make-docker-artifact [{:keys [main-namespace docker-registry lib-name version java-version
                                    aliases aot?] :or {aliases [] aot? true}}]
  (let [configuration (merge (if aot? aot-config {})
                             (bundle-config aot?)
                             docker-config
                             {::main-namespace  main-namespace
                              ::target-path     nil
                              ::docker-registry docker-registry
                              ::lib-name        lib-name
                              ::version         version
                              ::java-version    java-version
                              ::aliases         aliases})
        {:keys [::target-path]} (ig/init configuration [::target-path])]
    (clean target-path)
    (ig/init configuration)))
