(ns stan
  (:require [codesmith.anvil.artifacts :as art]
            [codesmith.anvil.shell :as sh]))

(comment
  (art/make-docker-artifact {:main-namespace    'codesmith.anvil.artifacts
                             :lib-name          'anvic
                             :aliases           []
                             :version           "1.0.0"
                             :java-version      :openjdk/jdk17
                             :docker-base-image "other-base-image"
                             :aot?              false})

  (sh/git-branch)
  )