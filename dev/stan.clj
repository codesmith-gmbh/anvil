(ns stan
  (:require [ch.codesmith.anvil.artifacts :as art]

            [clojure.tools.build.api :as b]))

(comment
  (art/make-docker-artifact {:main-namespace    'codesmith.anvil.artifacts
                             :lib-name          'anvic
                             :aliases           []
                             :version           "1.0.0"
                             :java-version      :openjdk/jdk17
                             :docker-base-image "other-base-image"
                             :basis-opts        {:user :standard}
                             :aot?              false})

  (keys
    (b/create-basis {:user :standard}))
  )