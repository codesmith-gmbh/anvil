(ns ch.codesmith.anvil.run
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps.alpha :as t]))

;; Taken from [build-clj](https://github.com/seancorfield/build-clj) by Sean Corfield
;; With modification to support basis-opts
;; TODO@stan: test if it works and propose PR to Sean Corfield
(defn run-task
  "Run a task based on aliases.

  If :main-args is not provided and no :main-opts are found
  in the aliases, default to the Cognitect Labs' test-runner."
  [{:keys [java-opts jvm-opts main main-args main-opts basis-opts] :as opts} aliases]
  (let [task     (str/join ", " (map name aliases))
        _        (println "\nRunning task for:" task)
        basis    (b/create-basis (merge basis-opts {:aliases aliases}))
        combined (t/combine-aliases basis aliases)
        cmds     (b/java-command
                   {:basis     basis
                    :java-opts (into (or java-opts (:jvm-opts combined))
                                     jvm-opts)
                    :main      (or 'clojure.main main)
                    :main-args (into (or main-args
                                         (:main-opts combined)
                                         ["-m" "cognitect.test-runner"])
                                     main-opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info (str "Task failed for: " task) {}))))
  opts)

(defn run-tests
  "Run tests.

  Always adds :test to the aliases."
  [{:keys [aliases] :as opts}]
  (-> opts (run-task (into [:test] aliases))))