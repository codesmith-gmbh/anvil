(ns ch.codesmith.anvil.apps
  (:require [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [buddy.core.hash :as hash]
            [buddy.core.codecs :as bc]
            [com.rpl.specter :as sp]
            [clojure.string :as str]
            ch.codesmith.anvil.io
            [ch.codesmith.anvil.basis :as ab]))

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

(defmulti copy-jar
          {:arglists '([lib props jar-dir target-dir])}
  (fn [_ props _ _]
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
  [lib props jar-dir _]
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
java ${JAVA_OPTS} -cp \"/lib/*:${DIR}/../lib/*\" clojure.main -m "
            main-namespace
            "\n"))
    (make-executable script-path)))


(def java-jdk-docker-base-images
  {:java8  "openjdk:8u302-slim"
   :java11 "openjdk:11.0.13-jdk-slim-buster"
   :java14 "openjdk:14.0.2-jdk-slim-buster"
   :java15 "openjdk:15.0.2-jdk-slim-buster"
   :java16 "openjdk:16.0.2-jdk-slim-buster"
   :java17 "openjdk:17.0.1-jdk-slim-buster"})

(def java-jre-docker-base-images
  {:java8  "openjdk:8u312-jre-slim-buster"
   :java11 "openjdk:11.0.13-jre-slim-buster"})

(def default-runtime-base-image "bitnami/minideb:buster-snapshot-20220112T215005Z")

(defmulti resolve-modules identity)

(defmethod resolve-modules :anvil [_]
  #{"java.base"
    "java.datatransfer"
    "java.instrument"
    "java.logging"
    "java.management"
    "java.management.rmi"
    "java.naming"
    "java.net.http"
    "java.prefs"
    "java.rmi"
    "java.security.jgss"
    "java.security.sasl"
    "java.sql"
    "java.sql.rowset"
    "java.transaction.xa"
    "java.xml"
    "java.xml.crypto"})

(defmethod resolve-modules :java.se [_]
  #{"java.se"})

(defmethod resolve-modules :java.base [_]
  #{"java.base"})

(defn resolve-java-runtime [{:keys [version type modules-profile extra-modules docker-base-image docker-jdk-base-image docker-runtime-base-image java-opts] :as runtime}]
  (assoc
    (cond
      docker-base-image {:docker-image-type :simple-image
                         :docker-base-image docker-base-image}
      (= type :jdk) {:docker-image-type :simple-image
                     :docker-base-image (version java-jdk-docker-base-images)}
      (= type :jre) (if-let [docker-base-image (version java-jre-docker-base-images)]
                      {:docker-image-type :simple-image
                       :docker-base-image docker-base-image}
                      {:docker-image-type         :jlink-image
                       :docker-jdk-base-image     (or docker-jdk-base-image (version java-jdk-docker-base-images))
                       :docker-runtime-base-image (or docker-runtime-base-image default-runtime-base-image)
                       :modules                   (into (resolve-modules modules-profile)
                                                        extra-modules)})
      :else (throw (ex-info "cannot resolve java runtime" {:java-runtime runtime})))
    :version version
    :java-opts java-opts))

(defmulti default-java-opts identity)

(defmethod default-java-opts
  :java17
  [_]
  "-XX:MaxRAMPercentage=85 -XX:+UseZGC")

(defmethod default-java-opts
  :default
  [_]
  "-XX:MaxRAMPercentage=85")

(defn app-dockerfile [{:keys [target-path java-version docker-base-image-name version java-opts]}]
  (println "Creating the App Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (str "FROM " docker-base-image-name "\n"
             "ENV VERSION=\"" version "\"\n"
             "ENV LOCATION=\":docker\"\n"
             "ENV JAVA_OPTS=\"" (or java-opts (default-java-opts java-version)) "\"\n"
             "COPY /app/ /app/\n"
             "CMD [\"/app/bin/run.sh\"]\n")))

(defn simple-base-image-dockerfile [{:keys [docker-base-image]}]
  (str "FROM " docker-base-image "\n"
       "COPY /lib/ /lib/\n"))

(defn jlink-image-dockerfile [{:keys [docker-jdk-base-image
                                      docker-runtime-base-image
                                      modules]}]
  (str "FROM " docker-jdk-base-image "\n"
       "RUN jlink --add-modules " (str/join "," modules) " --output /tmp/jre\n"
       "FROM " docker-runtime-base-image "\n"
       "COPY --from=0 /tmp/jre /jre\n"
       "ENV PATH=/jre/bin:$PATH\n"
       "COPY /lib/ /lib/\n"))

(defn lib-dockerfile [{:keys [target-path java-runtime]}]
  (println "Creating the Lib Dockerfile")
  (spit (fs/path target-path "Dockerfile")
        (case (:docker-image-type java-runtime)
          :simple-image (simple-base-image-dockerfile java-runtime)
          :jlink-image (jlink-image-dockerfile java-runtime))))

(defn tag-base [docker-registry lib]
  (str (if docker-registry (str docker-registry "/") "") (name lib) ":"))

(defn app-docker-tag [tag-base version]
  (str tag-base version))

(defn lib-docker-tag [tag-base basis java-runtime]
  (let [serialized-libs (pr-str
                          {:java-runtime (dissoc java-runtime :java-opts)
                           :libs         (into []
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
                                java-runtime
                                docker-registry
                                description-data
                                aot]
                         :or   {java-runtime {:version         :java17
                                              :type            :jre
                                              :modules-profile :anvil}}}]
  (let [root           (or root ".")
        root-deps      (str (io/file root "deps.edn"))
        basis          (or basis (ab/create-basis {:project root-deps}))
        target-dir     (or target-dir "target")
        ; 1. create the jar file for the project
        jar-file       (libs/jar {:lib              lib
                                  :version          version
                                  :with-pom?        false
                                  :root             root
                                  :basis            basis
                                  :target-dir       target-dir
                                  :description-data description-data
                                  :clean?           true
                                  :aot              aot})
        tag-base       (tag-base docker-registry lib)
        java-runtime   (resolve-java-runtime java-runtime)
        lib-docker-tag (lib-docker-tag tag-base basis java-runtime)
        docker-lib-dir (io/file target-dir "docker-lib")]
    ; 2. create the docker lib folder
    (copy-jars basis (io/file docker-lib-dir "lib") (io/file target-dir "libs"))
    (generate-docker-script {:target-path docker-lib-dir
                             :script-name "docker-build.sh"
                             :body        (docker-build-body lib-docker-tag)})
    (generate-docker-script {:target-path docker-lib-dir
                             :script-name "docker-push.sh"
                             :body        (docker-push-body lib-docker-tag)})
    (lib-dockerfile {:target-path  docker-lib-dir
                     :java-runtime java-runtime})
    ; 3. create the docker app folder
    (let [docker-app-dir (io/file target-dir "docker-app")
          app-dir        (io/file docker-app-dir "app")
          app-tag        (str tag-base version)
          latest-tag     (str tag-base "latest")]
      (b/copy-file {:src    jar-file
                    :target (str (io/file app-dir "lib" (fs/file-name jar-file)))})
      (libs/spit-version-file {:version version
                               :dir     app-dir})
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
   fi
fi
"
                                              (docker-build-body app-tag)
                                              (str "\ndocker tag " app-tag " " latest-tag))})
      (generate-docker-script {:target-path docker-app-dir
                               :script-name "docker-push.sh"
                               :body
                               (str/join "\n"
                                         ["../docker-lib/docker-push.sh"
                                          (docker-push-body app-tag)
                                          (docker-push-body latest-tag)])})
      (app-dockerfile {:target-path            docker-app-dir
                       :java-version           (:version java-runtime)
                       :docker-base-image-name lib-docker-tag
                       :version                version
                       :jar-file               jar-file
                       :java-opts              (:java-opts java-runtime)})
      {:app-docker-tag app-tag
       :lib-docker-tag lib-docker-tag})))
