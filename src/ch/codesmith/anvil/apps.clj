(ns ch.codesmith.anvil.apps
  (:require [babashka.fs :as fs]
            [buddy.core.codecs :as bc]
            [buddy.core.hash :as hash]
            [ch.codesmith.anvil.basis :as ab]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.libs :as libs]
            [ch.codesmith.logger :as log]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [com.rpl.specter :as sp]))

(log/deflogger)

(def anvil-epoch "0.10")

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
  [lib {:keys [git/tag git/sha deps/root anvil/version]} jar-dir target-dir]
  (let [target-dir     (io/file target-dir (nondir-full-name lib))
        version        (or version (if tag (str tag "-" sha) sha))
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

(defn copy-jars [basis lib-filter props-trans jar-dir target-dir]
  (let [all-libs (all-libs basis)]
    (doseq [[lib props] all-libs]
      (when (lib-filter props)
        (copy-jar lib (props-trans props) jar-dir target-dir)))))

(defn is-local-dep? [props]
  (:local/root props))

(defn copy-lib-jars [basis jar-dir target-dir]
  (copy-jars basis (complement is-local-dep?) identity jar-dir target-dir))

(defn copy-app-jars [basis local-version jar-dir target-dir]
  (copy-jars basis is-local-dep? #(assoc % :anvil/version local-version) jar-dir target-dir))

(defn make-executable [f]
  (fs/set-posix-file-permissions f "rwxr-xr-x"))

(defn default-run-script [& args]
  (str
    "#!/bin/bash
DIR=\"$( cd \"$( dirname \"${BASH_SOURCE[0]}\" )\" && pwd )\"
java -Dfile.encoding=UTF-8 ${JAVA_OPTS} -cp \"${DIR}/../lib/*:/lib/anvil/*\" "
    (str/join " " args)
    "\n"))

(defmulti clj-run-script :script-type)

(defmethod clj-run-script :clojure.main
  [{:keys [main-namespace]}]
  (default-run-script "clojure.main -m" main-namespace))

(defmethod clj-run-script :class
  [{:keys [main-namespace]}]
  (default-run-script main-namespace))

(defn generate-app-run-script [{:keys [target clj-runtime]}]
  (let [bin-dir-path (fs/path target "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
      (clj-run-script clj-runtime))
    (make-executable script-path)))

(def java-jdk-docker-base-images
  {:java8  "eclipse-temurin:8u382-b05-jdk-jammy",
   :java11 "eclipse-temurin:11.0.20.1_1-jdk-jammy",
   :java17 "eclipse-temurin:17.0.8.1_1-jdk-jammy",
   :java18 "eclipse-temurin:18.0.2.1_1-jdk-jammy",
   :java19 "eclipse-temurin:19.0.2_7-jdk-jammy",
   :java20 "eclipse-temurin:20.0.2_9-jdk-jammy",
   :java21 "eclipse-temurin:21_35-jdk-jammy"})

(def java-jre-docker-base-images
  {:java8  "eclipse-temurin:8u382-b05-jre-jammy",
   :java11 "eclipse-temurin:11.0.20.1_1-jre-jammy",
   :java17 "eclipse-temurin:17.0.8.1_1-jre-jammy",
   :java21 "eclipse-temurin:21_35-jre-jammy"})

(def default-runtime-base-image "ubuntu:jammy-20231004")

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
    "java.xml.crypto"
    "jdk.unsupported"})

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
                      (throw (ex-info (str "no jre images for java version " version)
                               {:version      version
                                :java-runtime runtime})))
      (= type :jlink) {:docker-image-type         :jlink-image
                       :docker-jdk-base-image     (or docker-jdk-base-image (version java-jdk-docker-base-images))
                       :docker-runtime-base-image (or docker-runtime-base-image default-runtime-base-image)
                       :modules                   (into (resolve-modules modules-profile)
                                                    extra-modules)}
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

(defn app-dockerfile [{:keys [target-path java-version docker-base-image-name version java-opts exposed-ports]}]
  (println "Creating the App Dockerfile")
  (spit (fs/path target-path "Dockerfile")
    (str/join "\n"
      (concat
        [(str "FROM " docker-base-image-name)]
        (map (fn [port]
               (str "EXPOSE " port))
          exposed-ports)
        [(str "ENV VERSION=\"" version "\"")
         "ENV LOCATION=\":docker\""
         (str "ENV JAVA_OPTS=\"" (or java-opts (default-java-opts java-version)) "\"")
         "COPY /app/ /app/"
         "CMD [\"/app/bin/run.sh\"]"]))))

(defn simple-base-image-dockerfile [{:keys [docker-base-image]}]
  (str "FROM " docker-base-image "\n"
    "COPY /lib/ /lib/anvil/\n"))

(defn jlink-image-dockerfile [{:keys [docker-jdk-base-image
                                      docker-runtime-base-image
                                      modules]}]
  (str "FROM " docker-jdk-base-image "\n"
    "RUN jlink --add-modules " (str/join "," modules) " --output /tmp/jre\n"
    "FROM " docker-runtime-base-image "\n"
    "COPY --from=0 /tmp/jre /jre\n"
    "ENV PATH=/jre/bin:$PATH\n"
    "COPY /lib/ /lib/anvil/\n"))

(defn lib-dockerfile [{:keys [target-path java-runtime]}]
  (println "Creating the Lib Dockerfile")
  (spit (fs/path target-path "Dockerfile")
    (case (:docker-image-type java-runtime)
      :simple-image (simple-base-image-dockerfile java-runtime)
      :jlink-image (jlink-image-dockerfile java-runtime))))

(defn tag-base [docker-registry lib]
  (let [namespace (namespace lib)]
    (str (if docker-registry (str docker-registry "/") "")
      (if namespace (str namespace "/") "")
      (name lib)
      ":")))

(defn app-docker-tag
  ([tag-base version]
   (str tag-base version))
  ([docker-registry lib version]
   (app-docker-tag (tag-base docker-registry lib) version)))

(defn lib-docker-tag [tag-base basis java-runtime]
  (let [serialized-libs (pr-str
                          {:java-runtime (dissoc java-runtime :java-opts)
                           :anvil-epoch  anvil-epoch
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
  (fs/create-dirs target-path)
  (let [script-file (fs/path target-path script-name)]
    (spit script-file
      (str "#!/bin/bash\n"
        "set -e\n"
        "dir=$(CDPATH= cd -- \"$(dirname -- \"$0\")\" && pwd)\n"
        "(
cd \"$dir\" || exit\n"
        "  " body
        "\n)\n"))
    (make-executable script-file)
    script-file))

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
                                clj-runtime
                                java-runtime
                                docker-image-options
                                docker-registry
                                description-data
                                aot]
                         :or   {java-runtime {:version         :java17
                                              :type            :jlink
                                              :modules-profile :anvil}}}]
  (when (and main-namespace clj-runtime)
    (throw (ex-info (str "only one of :main-namespace or :clj-runtime may be specified")
             {:main-namespace main-namespace
              :clj-runtime    clj-runtime})))
  (when main-namespace
    (log/warn-c {:main-namespace main-namespace} "Use of :main-namespace is deprecated, use :clj-runtime instead"))
  (when (and (= (:script-type clj-runtime) :class)
          (not aot))
    (throw (ex-info (str "using the script-type `:class` requires AOT compilation, however :aot is not defined") {})))
  (let [clj-runtime (or clj-runtime
                      {:main-namespace main-namespace
                       :script-type    :clojure.main})
        root        (fs/absolutize (fs/path (or root ".")))]
    (binding [b/*project-root* (str root)]
      (let [basis                   (or basis (ab/create-basis {}))
            target-dir              (str (or target-dir (fs/path root "target")))
            ; 1. create the jar file for the project
            jar-file                (libs/jar {:lib              lib
                                               :version          version
                                               :with-pom?        false
                                               :root             (str root)
                                               :basis            basis
                                               :target-dir       target-dir
                                               :description-data description-data
                                               :clean?           true
                                               :aot              aot})
            tag-base                (tag-base docker-registry lib)
            java-runtime            (resolve-java-runtime java-runtime)
            lib-docker-tag          (lib-docker-tag tag-base basis java-runtime)
            docker-lib-dir          (io/file target-dir "docker-lib")
            lib-docker-build-script (generate-docker-script {:target-path docker-lib-dir
                                                             :script-name "docker-build.sh"
                                                             :body        (docker-build-body lib-docker-tag)})
            lib-docker-push-script  (generate-docker-script {:target-path docker-lib-dir
                                                             :script-name "docker-push.sh"
                                                             :body        (docker-push-body lib-docker-tag)})]
        ; 2. create the docker lib folder
        (copy-lib-jars basis (io/file docker-lib-dir "lib") (io/file target-dir "libs"))
        (lib-dockerfile {:target-path  docker-lib-dir
                         :java-runtime java-runtime})
        ; 3. create the docker app folder
        (let [docker-app-dir          (io/file target-dir "docker-app")
              app-dir                 (io/file docker-app-dir "app")
              app-lib-dir             (io/file app-dir "lib")
              app-tag                 (app-docker-tag tag-base version)
              latest-tag              (app-docker-tag tag-base "latest")
              app-docker-build-script (generate-docker-script {:target-path docker-app-dir
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
     echo \"The base image " lib-docker-tag " does not exists remotely; building\"
     ../docker-lib/docker-build.sh
   fi
fi
"
                                                                              (docker-build-body app-tag)
                                                                              (str "\ndocker tag " app-tag " " latest-tag))})
              app-docker-push-script  (generate-docker-script {:target-path docker-app-dir
                                                               :script-name "docker-push.sh"
                                                               :body
                                                               (str/join "\n"
                                                                 ["../docker-lib/docker-push.sh"
                                                                  (docker-push-body app-tag)
                                                                  (docker-push-body latest-tag)])})]
          (copy-app-jars basis version app-lib-dir (io/file target-dir "libs"))
          (b/copy-file {:src    jar-file
                        :target (str (io/file app-lib-dir (fs/file-name jar-file)))})
          (libs/spit-version-file {:version version
                                   :dir     app-dir})
          (generate-app-run-script {:target      app-dir
                                    :clj-runtime clj-runtime})
          (app-dockerfile {:target-path            docker-app-dir
                           :java-version           (:version java-runtime)
                           :docker-base-image-name lib-docker-tag
                           :version                version
                           :exposed-ports          (:exposed-ports docker-image-options)
                           :jar-file               jar-file
                           :java-opts              (:java-opts java-runtime)})
          {:app-docker-tag     app-tag
           :lib-docker-tag     lib-docker-tag
           :lib-docker-scripts {:build lib-docker-build-script
                                :push  lib-docker-push-script}
           :app-docker-scripts {:build app-docker-build-script
                                :push  app-docker-push-script}})))))
