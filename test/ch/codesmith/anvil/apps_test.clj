(ns ch.codesmith.anvil.apps-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.apps :as apps]
            [ch.codesmith.anvil.shell :as sh]
            [clojure.tools.build.api :as b]
            [clojure.string :as str]
            [ch.codesmith.anvil.helloworld :as hw]))

(deftest nondir-full-name-correctness
  (is (= "a" (apps/nondir-full-name "a")))
  (is (= "a--b" (apps/nondir-full-name 'a/b)))
  (is (= "a--b--c" (apps/nondir-full-name :a/b "c"))))

(deftest full-jar-file-name-correctness
  (is (= "a--b--1.2.jar" (apps/full-jar-file-name 'a/b "1.2"))))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))

(defn test-docker-generator [with-aot?]
  (apps/docker-generator {:lib            lib
                          :version        version
                          :root           "."
                          :target-dir     "target"
                          :main-namespace "test"})
  (is true))


(deftest docker-generator-correctness
  (test-docker-generator false))

(defn start-registry! [port]
  (sh/sh! "docker" "run" "-d" "-p" (str port ":5000") "--name" "registry" "registry:2"))

(defn force-stop-registry! []
  (sh/sh! "docker" "rm" "-f" "registry"))

(defn docker-rmi! [tag]
  (sh/sh! "docker" "rmi" "-f" tag))

(defn registry-images []
  (str/split-lines
    (sh/sh "docker" "image" "ls" "-q" "localhost:5001/hello")))

(defn rm-registry-images! []
  (doseq [image (registry-images)]
    (docker-rmi! image)))

(deftest hello-world-correctness
  (let [docker-registry "localhost:5001"
        {:keys [app-docker-tag
                lib-docker-tag]} (apps/docker-generator
                                   (merge hw/base-properties
                                          {:java-runtime    {:version         :java17
                                                             :type            :jre
                                                             :modules-profile :java.base}
                                           :main-namespace  "hello"
                                           :docker-registry docker-registry}))
        port            5001]
    (try
      ; 1. cleanup
      (rm-registry-images!)
      ; 2. startup local registry
      (start-registry! port)
      ; 3. build push pull run
      (sh/sh! "./target/docker-app/docker-build.sh")
      (sh/sh! "./target/docker-app/docker-push.sh")
      (docker-rmi! app-docker-tag)
      (sh/sh! "docker" "pull" app-docker-tag)
      (sh/sh! "docker" "run" "--rm" app-docker-tag)
      ; 4. build with lib in registry
      (docker-rmi! app-docker-tag)
      (docker-rmi! lib-docker-tag)
      (sh/sh! "./target/docker-app/docker-build.sh")
      ; 5. shut down local registry
      (finally
        (force-stop-registry!))))
  (is true))