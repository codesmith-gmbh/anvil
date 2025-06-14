(ns ch.codesmith.anvil.apps
  (:require [babashka.fs :as fs]
            [buddy.core.codecs :as bc]
            [buddy.core.hash :as hash]
            [ch.codesmith.anvil.basis :as ab]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [com.rpl.specter :as sp]
            [taoensso.telemere :as t]))

(def anvil-epoch "0.11")

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
        {:keys [jar-file]} (libs/jar {:lib        lib
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
    "#!/bin/sh
java -Dfile.encoding=UTF-8 ${JAVA_OPTS} -cp \"/app/lib/*:/lib/anvil/*\" "
    (str/join " " args)
    "\n"))

(defmulti clj-run-script :script-type)

(defmethod clj-run-script :clojure.main
  [{:keys [main-namespace]}]
  (default-run-script "clojure.main -m" main-namespace))

(defmethod clj-run-script :class
  [{:keys [main-namespace]}]
  (default-run-script (str/replace main-namespace "-" "_")))

(defn generate-app-run-script [{:keys [target clj-runtime]}]
  (let [bin-dir-path (fs/path target "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
      (clj-run-script clj-runtime))
    (make-executable script-path)))

(def java-jdk-docker-base-images
  {:java8  "eclipse-temurin:8u452-b09-jdk-noble",
   :java11 "eclipse-temurin:11.0.27_6-jdk-noble",
   :java17 "eclipse-temurin:17.0.15_6-jdk-noble",
   :java21 "eclipse-temurin:21.0.7_6-jdk-noble",
   :java24 "eclipse-temurin:24.0.1_9-jdk-noble"})

(def java-jre-docker-base-images
  {:java8  "eclipse-temurin:8u452-b09-jre-noble",
   :java11 "eclipse-temurin:11.0.27_6-jre-noble",
   :java17 "eclipse-temurin:17.0.15_6-jre-noble",
   :java21 "eclipse-temurin:21.0.7_6-jre-noble"})

(def default-runtime-base-image "ubuntu:noble-20250529")

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

(defn resolve-java-runtime [{:keys [version type modules-profile extra-modules docker-base-image
                                    docker-jdk-base-image docker-runtime-base-image java-opts
                                    include-locales] :as runtime}]
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
                                                    (concat extra-modules
                                                      (when include-locales
                                                        ["jdk.localedata"])))}
      :else (throw (ex-info "cannot resolve java runtime" {:java-runtime runtime})))
    :version version
    :java-opts java-opts
    :include-locales include-locales))

(defmulti default-java-opts identity)

(defmethod default-java-opts
  :java17
  [_]
  "-XX:MaxRAMPercentage=85 -XX:+UseZGC")

(defmethod default-java-opts
  :java21
  [_]
  "-XX:MaxRAMPercentage=85 -XX:+UseZGC")

(defmethod default-java-opts
  :default
  [_]
  "-XX:MaxRAMPercentage=85")

(defn escape-double-quote [text]
  (str/escape text {\" "\\\""
                    \\ "\\"}))

(defn app-dockerfile [{:keys [target-path java-version
                              docker-base-image-name version
                              java-opts exposed-ports env-vars
                              volumes]}]
  (t/log! "Creating the App Dockerfile")
  (spit (fs/path target-path "Dockerfile")
    (str/join "\n"
      (concat
        [(str "FROM " docker-base-image-name)]
        (map (fn [port]
               (str "EXPOSE " port))
          exposed-ports)
        (map (fn [[key value]]
               (str "ENV " (name key) "=\"" (escape-double-quote value) "\""))
          (merge
            {:VERSION   version
             :LOCATION  ":docker"
             :JAVA_OPTS (or java-opts (default-java-opts java-version))}
            env-vars))
        (map (fn [volume]
               (str "VOLUME " volume))
          volumes)
        ["COPY /app/ /app/"
         "CMD [\"/app/bin/run.sh\"]"]))))

(defn simple-base-image-dockerfile [{:keys [docker-base-image]}]
  (str "FROM " docker-base-image "\n"
    "COPY /lib/ /lib/anvil/\n"))

(defn jlink-image-dockerfile [{:keys [docker-jdk-base-image
                                      docker-runtime-base-image
                                      modules include-locales]}]
  (let [modules-option (str "--add-modules " (str/join "," modules))
        locales-option (if include-locales
                         (str "--include-locales=" (str/join "," include-locales))
                         "")]
    (str "FROM " docker-jdk-base-image "\n"
      "RUN jlink " modules-option " " locales-option " --output /tmp/jre\n"
      "FROM " docker-runtime-base-image "\n"
      "COPY --from=0 /tmp/jre /jre\n"
      "ENV PATH=/jre/bin:$PATH\n"
      "COPY /lib/ /lib/anvil/\n")))

(defn lib-dockerfile [{:keys [target-path java-runtime]}]
  (t/log! "Creating the Lib Dockerfile")
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

(defn platform-option [platform-architecture]
  (if platform-architecture
    (str "--platform=" platform-architecture)
    ""))

(defn docker-build-body [{:keys [command platform-architecture]} tag]
  (str command " " (platform-option platform-architecture) " -t " tag " ."))

(defn docker-tag [existing-tag new-tag]
  (str "docker tag " existing-tag " " new-tag))

(defn docker-push-body [tag]
  (str "docker push " tag))

(defn docker-generator [{:keys [lib
                                version
                                root
                                basis-creation-fn
                                target-dir
                                main-namespace
                                clj-runtime
                                java-runtime
                                docker-image-options
                                docker-build-config
                                docker-registry
                                description-data
                                aot
                                clean?]
                         :or   {java-runtime        {:version         :java17
                                                     :type            :jlink
                                                     :modules-profile :anvil}
                                docker-build-config {:command "docker build"}
                                clean?              true}}]
  (when (and main-namespace clj-runtime)
    (throw (ex-info (str "only one of :main-namespace or :clj-runtime may be specified")
             {:main-namespace main-namespace
              :clj-runtime    clj-runtime})))
  (when main-namespace
    (t/log! {:main-namespace main-namespace
             :level          :warn}
      "Use of :main-namespace is deprecated, use :clj-runtime instead"))
  (when (and (= (:script-type clj-runtime) :class)
          (not aot))
    (throw (ex-info (str "using the script-type `:class` requires AOT compilation, however :aot is not defined") {})))
  (let [clj-runtime (or clj-runtime
                        {:main-namespace main-namespace
                         :script-type    :clojure.main})
        root        (fs/absolutize (fs/path (or root ".")))]
    (binding [libs/*basis-creation-fn* (or basis-creation-fn ab/create-basis)
              b/*project-root*         (str root)]
      (let [target-dir              (str (or target-dir (fs/path root "target")))
            ; 1. create the jar file for the project
            {:keys [jar-file
                    basis]} (libs/jar {:lib              lib
                                       :version          version
                                       :with-pom?        false
                                       :root             (str root)
                                       :target-dir       target-dir
                                       :description-data description-data
                                       :clean?           clean?
                                       :aot              aot})
            tag-base                (tag-base docker-registry lib)
            docker-build-config     (merge (select-keys [:platform-architecture] java-runtime)
                                      docker-build-config)
            java-runtime            (resolve-java-runtime java-runtime)
            lib-docker-tag          (lib-docker-tag tag-base basis java-runtime)
            docker-lib-dir          (io/file target-dir "docker-lib")
            lib-docker-build-script (generate-docker-script {:target-path docker-lib-dir
                                                             :script-name "docker-build.sh"
                                                             :body        (docker-build-body docker-build-config lib-docker-tag)})
            lib-docker-push-script  (generate-docker-script {:target-path docker-lib-dir
                                                             :script-name "docker-push.sh"
                                                             :body        (docker-push-body lib-docker-tag)})]
        ; 2. create the docker lib folder
        (t/log! {:level :debug} "copying lib jars")
        (copy-lib-jars basis (io/file docker-lib-dir "lib") (io/file target-dir "libs"))
        (lib-dockerfile {:target-path  docker-lib-dir
                         :java-runtime java-runtime})
        ; 3. create the docker app folder
        (let [docker-app-dir          (io/file target-dir "docker-app")
              app-dir                 (io/file docker-app-dir "app")
              app-lib-dir             (io/file app-dir "lib")
              app-tag                 (app-docker-tag tag-base version)
              versioned-app-tag       (app-docker-tag tag-base "\"${VERSION}\"")
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
                                                                              (docker-build-body docker-build-config app-tag)
                                                                              "\n"
                                                                              (docker-tag app-tag latest-tag))})
              app-docker-push-script  (generate-docker-script {:target-path docker-app-dir
                                                               :script-name "docker-push.sh"
                                                               :body        (str "
../docker-lib/docker-push.sh
VERSION=\"$1\"
if [ -z \"$VERSION\" ]
then
  " (docker-push-body app-tag) "
else
  " (docker-tag app-tag versioned-app-tag) "\n  " (docker-push-body versioned-app-tag) "
fi
" (docker-push-body latest-tag) "
"
                                                                              )})]
          (t/log! {:level :debug} "copying app jars")
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
                           :env-vars               (:env-vars docker-image-options)
                           :volumes                (:volumes docker-image-options)
                           :jar-file               jar-file
                           :java-opts              (:java-opts java-runtime)})
          (t/log! {:data {:app-docker-tag app-docker-tag
                          :lib-docker-tag lib-docker-tag}}
            "docker tags")
          {:app-docker-tag     app-tag
           :lib-docker-tag     lib-docker-tag
           :lib-docker-scripts {:build lib-docker-build-script
                                :push  lib-docker-push-script}
           :app-docker-scripts {:build app-docker-build-script
                                :push  app-docker-push-script}})))))
