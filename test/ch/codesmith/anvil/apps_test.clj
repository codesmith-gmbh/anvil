(ns ch.codesmith.anvil.apps-test
  (:require [clojure.test :refer :all]
            [ch.codesmith.anvil.apps :as apps]
            [clojure.tools.build.api :as b]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.deps.alpha :as deps]
            [clojure.string :as str]
            [clojure.java.shell :as js])
  (:import (java.util List)))

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
                          :java-version   :openjdk/jdk17
                          :target-dir     "target"
                          :main-namespace "test"})
  (is true))


(deftest docker-generator-correctness
  (test-docker-generator false))

(defn sh [& args]
  (let [{:keys [exit out] :as result} (apply js/sh args)]
    (if (= exit 0)
      (str/trim out)
      (throw (ex-info "shell error"
                      (assoc result
                        :args args))))))

(defn sh! [& args]
  "Execute the given shell command and redirect the ouput/error to the standard output error; returns nil."
  (let [^Process process (.. (ProcessBuilder. ^List args)
                             (inheritIO)
                             (start))
        exit             (.waitFor process)]
    (when (not= exit 0)
      (throw (ex-info "shell error"
                      {:exit 0
                       :args args})))))

(defn start-registry! [port]
  (sh! "docker" "run" "-d" "-p" (str port ":5000") "--name" "registry" "registry:2"))

(defn force-stop-registry! [port]
  (sh! "docker" "rm" "-f" "registry"))

(defn docker-rmi! [tag]
  (sh! "docker" "rmi" tag))

(defn registry-images []
  (str/split-lines
    (sh "docker" "image" "ls" "-q" "localhost:5001/hello")))

(defn rm-registry-images! []
  (doseq [image (registry-images)]
    (docker-rmi! image)))

(deftest hello-world-correctness
  (let [docker-registry "localhost:5001"
        lib             'hello
        {:keys [app-docker-tag
                lib-docker-tag]} (apps/docker-generator
                                   {:lib             lib
                                    :version         version
                                    :root            "test/helloworld"
                                    :java-version    :openjdk/jdk17
                                    :target-dir      "target"
                                    :main-namespace  "hello"
                                    :docker-registry docker-registry})
        port            5001]
    (try
      ; 1. cleanup
      (rm-registry-images!)
      ; 2. startup local registry
      (start-registry! port)
      ; 3. build push pull run
      (sh! "./target/docker-app/docker-build.sh")
      (sh! "./target/docker-app/docker-push.sh")
      (docker-rmi! app-docker-tag)
      (sh! "docker" "pull" app-docker-tag)
      (sh! "docker" "run" "--rm" app-docker-tag)
      ; 4. build with lib in registry
      (docker-rmi! app-docker-tag)
      (docker-rmi! lib-docker-tag)
      (sh! "./target/docker-app/docker-build.sh")
      ; 5. shut down local registry
      (finally
        (force-stop-registry! port))))
  (is true))