(ns ch.codesmith.anvil.apps
  (:require [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as bc]
            [com.rpl.specter :as sp]
            [clojure.string :as str])
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

(defn nondir-full-name
  "Creates a name separated by '--' instead of '/'; named stuff get separated"
  [& args]
  (-> (str/join "--" (map (fn [arg]
                            (if (keyword? arg)
                              (subs (str arg) 1)
                              arg))
                          args))
      (str/replace "/" "--")
      (str/replace "\\" "--")
      (str/replace ":" "--")))

(defn full-jar-file-name [lib version]
  (nondir-full-name lib (str version ".jar")))

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
  [lib {:keys [git/tag git/sha deps/root]} jar-dir target-dir]
  (let [target-dir     (io/file target-dir (nondir-full-name lib))
        version        (if tag (str tag "-" sha) sha)
        jar-file       (libs/jar {:lib        lib
                                  :version    version
                                  :with-pom?  false
                                  :root       root
                                  :target-dir target-dir
                                  :clean?     true})
        copy-file-args {:src    jar-file
                        :target (str (io/file jar-dir (full-jar-file-name lib version)))}]
    (b/copy-file copy-file-args)))

(defmethod copy-jar
  :mvn
  [lib props jar-dir target-dir]
  (let [jar-file (full-jar-file-name lib (:mvn/version props))
        path     (sp/select-one! [:paths sp/ALL] props)]
    (b/copy-file {:src    path
                  :target (str (io/file jar-dir jar-file))})))

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

(defn make-executable [f]
  (fs/set-posix-file-permissions f "rwxr-xr-x"))

(defn generate-app-run-script [{:keys [target main-namespace]}]
  (let [bin-dir-path (fs/path target "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
          (str
            "#!/bin/bash
DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"
java ${JAVA_OPTS} -cp \"${DIR}/../lib/*:/lib/*\" clojure.main -m "
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

(defn app-dockerfile [{:keys [target-path java-version docker-base-image-name version jar-file]}]
  (println "Creating the App Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " docker-base-image-name "\n"
             "ENV VERSION=\"" version "\"\n"
             "ENV LOCATION=\":docker\"\n"
             "ENV JAVA_OPTS=\"" (default-java-opts java-version) "\"\n"
             "COPY /app/ /app/\n"
             "CMD [\"/app/bin/run.sh\"]\n")))

(defn lib-dockerfile [{:keys [target-path java-docker-base-image]}]
  (println "Creating the Lib Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " java-docker-base-image "\n"
             "COPY /lib/ /lib/\n")))

(defn tag-base [docker-registry lib]
  (str (if docker-registry (str docker-registry "/") "") (name lib) ":"))

(defn app-docker-tag [tag-base version]
  (str tag-base version))

(defn lib-docker-tag [tag-base basis docker-lib-base-image]
  (let [serialized-libs (pr-str
                          {:docker-base-image docker-lib-base-image
                           :libs              (into []
                                                    (map (fn [[lib coords]]
                                                           [lib (dissoc coords :dependents :parents :paths :exclusions
                                                                        :deps/root)]))
                                                    (all-libs basis))})
        hash            (bc/bytes->hex (hash/sha3-256 serialized-libs))]
    (str tag-base "lib-" hash)))

(defn generate-docker-script [{:keys [target-path
                                      script-name
                                      body]}]
  (let [script-file (fs/path target-path script-name)]
    (spit script-file
          (str "#!/bin/bash\n"
               "set -e\n"
               "dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
               "(
  cd \"$dir\" || exit\n"
               "  " body
               "\n)\n"))
    (make-executable script-file)))

(defn docker-build-body [tag]
  (str "docker build -t " tag " ."))

(defn docker-push-body [tag]
  (str "docker push " tag))

(defn docker-generator [{:keys [lib
                                version
                                root
                                basis
                                target-dir
                                main-namespace
                                java-version
                                docker-base-image-override
                                docker-registry]}]
  (let [root                  (or root ".")
        root-deps             (str (io/file root "deps.edn"))
        basis                 (or basis (b/create-basis {:project root-deps}))
        target-dir            (or target-dir "target")
        ; 1. create the jar file for the project
        jar-file              (libs/jar {:lib        lib
                                         :version    version
                                         :with-pom?  false
                                         :root       root
                                         :basis      basis
                                         :target-dir target-dir
                                         :clean?     true})
        tag-base              (tag-base docker-registry lib)
        docker-lib-base-image (or docker-base-image-override
                                  (java-version java-docker-base-images))
        lib-docker-tag        (lib-docker-tag tag-base basis docker-lib-base-image)]
    ; 2. create the docker lib folder
    (let [docker-lib-dir (io/file target-dir "docker-lib")]
      (copy-jars basis (io/file docker-lib-dir "lib") (io/file target-dir "libs"))
      (generate-docker-script {:target-path docker-lib-dir
                               :script-name "docker-build.sh"
                               :body        (docker-build-body lib-docker-tag)})
      (generate-docker-script {:target-path docker-lib-dir
                               :script-name "docker-push.sh"
                               :body        (docker-push-body lib-docker-tag)})
      (lib-dockerfile {:target-path            docker-lib-dir
                       :java-docker-base-image docker-lib-base-image})
      ; 3. create the docker app folder
      (let [docker-app-dir (io/file target-dir "docker-app")
            app-dir        (io/file docker-app-dir "app")
            app-tag        (str tag-base version)]
        (b/copy-file {:src    jar-file
                      :target (str (io/file app-dir "lib" (fs/file-name jar-file)))})
        (generate-app-run-script {:target         app-dir
                                  :main-namespace main-namespace})
        (generate-docker-script {:target-path docker-app-dir
                                 :script-name "docker-build.sh"
                                 :body        (str
                                                "
  if docker image inspect " lib-docker-tag " >/dev/null; then
   echo \"The base image " lib-docker-tag "  exists\"
else
   echo \"The base image " lib-docker-tag " does not exists locally; pulling\"
   if docker pull " lib-docker-tag " >/dev/null; then
     echo \"Base image " lib-docker-tag " pulled.\"
   else
     echo \"The base image " lib-docker-tag " does not exists remotely; building and pushing\"
     ../docker-lib/docker-build.sh
     ../docker-lib/docker-push.sh
   fi
fi
"
                                                (docker-build-body app-tag)
                                                )})
        (generate-docker-script {:target-path docker-app-dir
                                 :script-name "docker-push.sh"
                                 :body        (docker-push-body app-tag)})
        (app-dockerfile {:target-path            docker-app-dir
                         :java-version           java-version
                         :docker-base-image-name lib-docker-tag
                         :version                version
                         :jar-file               jar-file})
        {:app-docker-tag app-tag
         :lib-docker-tag lib-docker-tag}))))
