(ns ch.codesmith.anvil.apps-test
  (:require [ch.codesmith.anvil.apps :as apps]
            [ch.codesmith.anvil.core :as ac]
            [ch.codesmith.anvil.helloworld :as hw]
            [ch.codesmith.anvil.shell :as sh]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.build.api :as b]
            [taoensso.telemere :as t]))

(deftest nondir-full-name-correctness
  (is (= "a" (apps/nondir-full-name "a")))
  (is (= "a--b" (apps/nondir-full-name 'a/b)))
  (is (= "a--b--c" (apps/nondir-full-name :a/b "c"))))

(deftest full-jar-file-name-correctness
  (is (= "a--b--1.2.jar" (apps/full-jar-file-name 'a/b "1.2"))))

(def lib 'ch.codesmith/anvil)
(def version (str "0.2." (b/git-count-revs {})))

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

(defn test-hello-world [aot script-type]
  (hw/with-root-dir
    (ac/clean-target-dir)
    (let [docker-registry "localhost:5001"
          config          (merge hw/base-properties
                            {:basis      (b/create-basis)
                             :jar        {:opts nil
                                          :aot  aot}
                             :image-base {:registry docker-registry}
                             :lib-image  {:base-image :jre25}
                             :app-image  {:script {:type           script-type
                                                   :main-namespace "test.hello"}}
                             })
          app-docker-tag  (apps/app-docker-tag config)
          lib-docker-tag  (apps/lib-image-tag config)
          port            5001]
      (t/log! {:data {:app-docker-tag app-docker-tag
                      :lib-docker-tag lib-docker-tag}} "test-hello-world")
      (try
        ; 1. cleanup
        (rm-registry-images!)
        ; 2. startup local registry
        (start-registry! port)
        ; 3. build push pull run
        (apps/docker-build-image :app-image config)
        (apps/docker-push-image :app-image config)
        (docker-rmi! app-docker-tag)
        (sh/sh! "docker" "pull" app-docker-tag)
        (let [out       (sh/sh "docker" "run" "--rm" app-docker-tag)
              last-line (last (str/split-lines out))
              {:keys [resource version-file implementation-version]} (edn/read-string last-line)]
          (is (= version-file {:version version}))
          (is (= implementation-version (if aot version "undefined")))
          (is (= resource (str "jar:file:/app/lib/hello-" version ".jar!/resource.edn"))))
        ; 4. build with lib in registry
        (docker-rmi! app-docker-tag)
        (docker-rmi! lib-docker-tag)
        (apps/docker-build-image :app-image config)
        ; 5. shut down local registry
        (finally
          (force-stop-registry!))))))

(deftest hello-world-correctness
  (testing "without aot / clojure.main"
    (test-hello-world nil :clojure.main))
  (testing "with aot / clojure.main"
    (test-hello-world {} :clojure.main))
  (testing "with aot / class"
    (test-hello-world {} :class)))

(deftest hello-world-completeness
  (testing "without aot / class"
    (is (thrown-with-msg?
          Exception
          #"using the script type `:class` requires AOT compilation, however :aot is not defined"
          (test-hello-world nil :class)))))