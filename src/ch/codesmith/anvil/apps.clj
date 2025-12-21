(ns ch.codesmith.anvil.apps
  (:require [babashka.fs :as fs]
            [babashka.process :as ps]
            [buddy.core.codecs :as bc]
            [buddy.core.hash :as hash]
            [ch.codesmith.anvil.core :as ac]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [com.rpl.specter :as sp]
            [taoensso.telemere :as t])
  (:import (com.google.cloud.tools.jib.api Containerizer DockerDaemonImage Jib JibContainerBuilder RegistryImage TarImage)
           (com.google.cloud.tools.jib.api.buildplan AbsoluteUnixPath FileEntriesLayer FileEntriesLayer$Builder FilePermissions Platform Port)
           (java.nio.file Path)))

(def anvil-epoch "0.12")

(defmulti default-java-opts identity)

(defmethod default-java-opts
  :java8
  [_]
  "-XX:MaxRAMPercentage=80")

(defmethod default-java-opts
  :java11
  [_]
  "-XX:MaxRAMPercentage=80")

(defmethod default-java-opts
  :default
  [_]
  "-XX:MaxRAMPercentage=80 -XX:+UseZGC")

(defn normalize-config [{:keys [version lib-image] :as config}]
  (-> config
    (update-in [:app-image :options :env-vars]
      #(merge {:VERSION   version
               :LOCATION  ":docker"
               :JAVA_OPTS (default-java-opts (:java-version lib-image))}
         %))))

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
  {:arglists '([lib props jar-dir])}
  (fn [_ props _]
    (:deps/manifest props)))

(defmethod copy-jar
  :deps
  [lib {:keys [git/tag git/sha anvil/version]} jar-dir]
  (let [target-dir     (io/file (ac/target-dir) "copy-jar" (nondir-full-name lib))
        version        (or version (if tag (str tag "-" sha) sha))
        {:keys [jar-file]} (libs/jar {:lib        lib
                                      :version    version
                                      :with-pom?  false
                                      :target-dir target-dir
                                      :clean?     true})
        copy-file-args {:src    jar-file
                        :target (str (io/file jar-dir (full-jar-file-name lib version)))}]
    (b/copy-file copy-file-args)))

(defmethod copy-jar
  :mvn
  [lib props jar-dir]
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

(defn copy-jars [basis lib-filter props-trans jar-dir]
  (let [all-libs (all-libs basis)]
    (doseq [[lib props] all-libs]
      (when (lib-filter props)
        (copy-jar lib (props-trans props) jar-dir)))))

(defn is-local-dep? [props]
  (:local/root props))

(defn copy-lib-jars [basis jar-dir]
  (copy-jars basis (complement is-local-dep?) identity jar-dir))

(defn copy-app-jars [basis local-version jar-dir]
  (copy-jars basis is-local-dep? #(assoc % :anvil/version local-version) jar-dir))

(defn make-executable [f]
  (fs/set-posix-file-permissions f "rwxr-xr-x"))

(defn default-run-script [& args]
  (str
    "#!/bin/sh
java -Dfile.encoding=UTF-8 ${JAVA_OPTS} -cp \"/app/lib/*:/lib/anvil/*\" "
    (str/join " " args)
    "\n"))

(defmulti clj-run-script :type)

(defmethod clj-run-script :clojure.main
  [{:keys [main-namespace]}]
  (default-run-script "clojure.main -m" main-namespace))

(defmethod clj-run-script :class
  [{:keys [main-namespace]}]
  (default-run-script (str/replace main-namespace "-" "_")))

(defn generate-app-run-script [target-dir script]
  (let [bin-dir-path (fs/path target-dir "bin")
        script-path  (fs/path bin-dir-path "run.sh")]
    (fs/create-dirs bin-dir-path)
    (spit script-path
      (clj-run-script script))
    (make-executable script-path)))

(def predefined-images
  {:jdk8  "eclipse-temurin:8u472-b08-jdk-noble",
   :jdk11 "eclipse-temurin:11.0.29_7-jdk-noble",
   :jdk17 "eclipse-temurin:17.0.17_10-jdk-noble",
   :jdk21 "eclipse-temurin:21.0.9_10-jdk-noble",
   :jdk25 "eclipse-temurin:25.0.1_8-jdk-noble"

   :jre8  "eclipse-temurin:8u472-b08-jre-noble",
   :jre11 "eclipse-temurin:11.0.29_7-jre-noble",
   :jre17 "eclipse-temurin:17.0.17_10-jre-noble",
   :jre21 "eclipse-temurin:21.0.9_10-jre-noble",
   :jre25 "eclipse-temurin:25.0.1_8-jre-noble"
   })

(defn resolve-base-image [{:keys [base-image]}]
  (get predefined-images base-image base-image))

(defn simple-base-image-dockerfile [lib-image]
  (str "FROM " (resolve-base-image lib-image) "\n"
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

(defn generate-lib-dockerfile [target-path lib-image]
  (t/log! "Creating the Lib Dockerfile")
  (spit (fs/path target-path "Dockerfile")
    (simple-base-image-dockerfile lib-image)
    #_(case (:docker-image-type lib-image)
        :simple-image (simple-base-image-dockerfile lib-image)
        :jlink-image (jlink-image-dockerfile lib-image))))

(defn tag-base [{:keys [lib image-base]}]
  (or (:tag-base image-base)
    (let [registry  (:registry image-base)
          namespace (namespace lib)]
      (str (if registry (str registry "/") "")
        (if namespace (str namespace "/") "")
        (name lib)
        ":"))))

(defn app-docker-tag
  ([config]
   (app-docker-tag config (:version config)))
  ([config version]
   (str (tag-base config) version)))

(defn lib-image-tag ^String [{:keys [basis
                                     image-base
                                     lib-image]
                              :as   config}]
  (let [serialized-libs (pr-str
                          {:image-base  image-base
                           :lib-image   lib-image
                           :anvil-epoch anvil-epoch
                           :libs        (into []
                                          (map (fn [[lib coords]]
                                                 [lib (dissoc coords :dependents :parents :paths :exclusions)]))
                                          (all-libs basis))})
        hash            (bc/bytes->hex (hash/sha3-256 serialized-libs))]
    (str (tag-base config) "lib-" hash)))

(defn platform-option [platform]
  (if platform
    (str "--platform=" platform)
    ""))

(defn docker-builder-config [{:keys [base-image]}]
  (let [platform       (sp/select-one [:platforms sp/ALL] base-image)
        builder-config (assoc (:builder base-image) :platform platform)]
    builder-config))

(defn docker-build-body [config tag]
  (let [{:keys [build-command platform]} (docker-builder-config config)]
    (str "docker " (or build-command "build") " " (platform-option platform) " -t " tag " .")))

(defn docker-tag [existing-tag new-tag]
  (str "docker tag " existing-tag " " new-tag))

(defn docker-push-body [tag]
  (str "docker push " tag))

(defn lib-image-target-dir []
  (io/file (ac/target-dir) "lib-image"))

(defn prepare-lib-image-build [{:keys [basis]}]
  (let [lib-image-target-dir (lib-image-target-dir)
        lib-image-lib-dir    (io/file lib-image-target-dir "lib")]
    (copy-lib-jars basis lib-image-lib-dir)
    {:target-dir (b/resolve-path lib-image-target-dir)
     :lib-dir    (b/resolve-path lib-image-lib-dir)}))


(defmulti docker-build-image
  {:arglists '([image-type config])}
  (fn [image-type _]
    image-type))

(defmulti docker-push-image
  {:arglists '([image-type config])}
  (fn [image-type _]
    image-type))

(defmethod docker-build-image :lib-image
  [_ {:keys [lib-image] :as config}]
  (let [{:keys [target-dir]} (prepare-lib-image-build config)
        image-tag (lib-image-tag config)]
    (generate-lib-dockerfile target-dir lib-image)
    (ps/shell {:dir target-dir} (docker-build-body config image-tag))))

(defmethod docker-push-image :lib-image
  [_ config]
  (let [image-tag (lib-image-tag config)]
    (ps/shell (docker-push-body image-tag))))

(defn app-image-target-dir []
  (io/file (ac/target-dir) "app-image"))

(defn compile-app [{:keys [lib
                           version
                           jar
                           app-image
                           basis]
                    :as   config}]
  (when (and (= (-> app-image :script :type) :class)
          (not (:aot jar)))
    (throw (IllegalArgumentException. "using the script type `:class` requires AOT compilation, however :aot is not defined")))
  (let [{:keys [jar-file]} (libs/jar (merge {:lib       lib
                                             :version   version
                                             :with-pom? false
                                             :clean?    false
                                             :basis     basis}
                                       jar))
        app-image-target-dir (app-image-target-dir)
        app-image-app-dir    (io/file app-image-target-dir "app")
        resolved-target-dir  (b/resolve-path app-image-target-dir)
        resolved-app-dir     (b/resolve-path app-image-app-dir)]
    (b/copy-file {:src    jar-file
                  :target (str (io/file app-image-app-dir
                                 "lib"
                                 (.getName (io/file jar-file))))})
    (generate-app-run-script
      resolved-app-dir
      (:script app-image))
    (libs/spit-version-file {:version version
                             :dir     app-image-app-dir})
    {:target-dir resolved-target-dir
     :app-dir    resolved-app-dir
     :image-tag  (app-docker-tag config version)}))

(defn escape-double-quote [text]
  (str/escape text {\" "\\\""
                    \\ "\\"}))

(defn generate-app-dockerfile [target-dir {:keys [app-image]
                                           :as   config}]
  (t/log! "Creating the App Dockerfile")
  (spit (io/file target-dir "Dockerfile")
    (str/join "\n"
      (concat
        [(str "FROM " (lib-image-tag config))]
        (map (fn [port]
               (str "EXPOSE " port))
          (:exposed-ports app-image))
        (map (fn [[key value]]
               (str "ENV " (name key) "=\"" (escape-double-quote value) "\""))
          (:env-vars app-image))
        (map (fn [volume]
               (str "VOLUME " volume))
          (:volumes app-image))
        ["COPY /app/ /app/"
         "CMD [\"/app/bin/run.sh\"]"]))))

(defmethod docker-build-image :app-image
  [_ config]
  (let [tag-name (app-docker-tag config)
        {:keys [target-dir]} (compile-app config)
        build!   #(ps/shell {:dir target-dir} (docker-build-body config tag-name))]
    (generate-app-dockerfile target-dir config)
    (try
      (build!)
      (catch Exception _
        (docker-build-image :lib-image config)
        (build!)))))

(defn image-exists-locally? [image-tag]
  (->
    (ps/sh "docker images -q" image-tag)
    (ps/check)
    :out
    str/blank?
    not))

(defmethod docker-push-image :app-image
  [_ config]
  (when (image-exists-locally? (lib-image-tag config))
    (docker-push-image :lib-image config))
  (ps/shell (docker-push-body (app-docker-tag config))))

(defn image-ref [image-spec image-tag]
  (into [(first image-spec) image-tag]
    (rest image-spec)))

(defmulti jib-build-image
  {:arglists '([image-type image-spec config])}
  (fn [image-type _ _]
    image-type))

(defn jib-image-ref [[type ^String image-tag {:keys [username password]}]]
  (case type
    :registry (let [image (RegistryImage/named image-tag)]
                (when (and username password)
                  (.addCredential image username password))
                image)
    :tar (TarImage/at (fs/path image-tag))
    :local (DockerDaemonImage/named image-tag)))

(defn reduce-jib-image-builder [base-image xfs]
  (transduce
    (keep identity)
    (completing
      (fn [builder f]
        (f builder)))
    (Jib/from (jib-image-ref base-image))
    xfs))

(defn jib-files-layer-xf [^FileEntriesLayer files]
  (fn [^JibContainerBuilder builder]
    (.addFileEntriesLayer builder files)))

(defn jib-dir-content-layer-xf [name src dest]
  (let [root        (fs/path src)
        destination (fs/path dest)
        files       (transduce
                      (filter fs/regular-file?)
                      (fn
                        ([^FileEntriesLayer$Builder builder]
                         (.build builder))
                        ([^FileEntriesLayer$Builder builder
                          ^Path file]
                         (.addEntry
                           builder
                           file
                           (AbsoluteUnixPath/fromPath
                             (fs/path destination (fs/relativize root file)))
                           (FilePermissions/fromPosixFilePermissions
                             (fs/posix-file-permissions file)))))
                      (.setName
                        (FileEntriesLayer/builder)
                        name)
                      (fs/glob root
                        "**"))]
    (jib-files-layer-xf files)))

(defn jib-platforms-xf [config]
  (when-let [platforms (-> config :image-base :platforms)]
    (fn [^JibContainerBuilder builder]
      (.setPlatforms builder (into #{}
                               (map (fn [{:keys [os architecture]}]
                                      (Platform.
                                        architecture
                                        os)))
                               platforms)))))

(defn jib-image-xf [image-ref]
  (fn [^JibContainerBuilder builder]
    (.containerize builder
      (Containerizer/to
        (jib-image-ref image-ref)))))

(defmethod jib-build-image :lib-image
  [_ image-spec {:keys [lib-image]
                 :as   config}]
  (let [{:keys [lib-dir]} (prepare-lib-image-build config)]
    (reduce-jib-image-builder
      (resolve-base-image lib-image)
      [(jib-dir-content-layer-xf "lib" lib-dir "/lib/anvil/")
       (jib-platforms-xf config)
       (jib-image-xf (image-ref image-spec (lib-image-tag config)))])))

(defn jib-volumes-xf [volumes]
  (when volumes
    (fn [^JibContainerBuilder builder]
      (reduce
        (fn [^JibContainerBuilder builder volume]
          (.addVolume builder (AbsoluteUnixPath/get volume)))
        builder
        volumes))))

(defn to-port ^Port [port]
  (if (integer? port)
    (Port/tcp port)
    (case (first port)
      :udp (Port/udp port)
      :tcp (Port/tcp port))))

(defn jib-exposed-ports-xf [exposed-ports]
  (when exposed-ports
    (fn [^JibContainerBuilder builder]
      (reduce
        (fn [^JibContainerBuilder builder port]
          (.addVolume builder (to-port port)))
        builder
        exposed-ports))))

(defn jib-env-vars-xf [env-vars]
  (when env-vars
    (fn [^JibContainerBuilder builder]
      (reduce
        (fn [^JibContainerBuilder builder [key value]]
          (.addEnvironmentVariable builder (name key) (str value)))
        builder
        env-vars))))

(defn jib-run-app-xf []
  (fn [^JibContainerBuilder builder]
    (.setProgramArguments builder ["/app/bin/run.sh"])))

(defmethod jib-build-image :app-image
  [_
   image-spec
   {:keys [app-image]
    :as   config}]
  (let [{:keys [image-tag app-dir]} (compile-app config)
        builder-fn #(reduce-jib-image-builder
                      (image-ref image-spec (lib-image-tag config))
                      [(jib-platforms-xf config)
                       (jib-volumes-xf (:volumes app-image))
                       (jib-exposed-ports-xf (:expored-ports app-image))
                       (jib-env-vars-xf (:env-vars app-image))
                       (jib-run-app-xf)
                       (jib-dir-content-layer-xf "app" app-dir "/app/")
                       (jib-image-xf (image-ref image-spec image-tag))])]
    (try
      (t/log! "build")
      (builder-fn)
      (catch Exception _
        (t/log! "build image first")
        (jib-build-image :lib-image image-spec config)
        (builder-fn)))))
