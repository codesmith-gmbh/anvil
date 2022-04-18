(ns stan
  (:require [ch.codesmith.anvil.apps :as apps]
            [ch.codesmith.anvil.basis :as ab]))

(comment

  (apps/make-docker-artifact {:main-namespace 'codesmith.anvil.artifacts
                              :lib-name       'anvic
                              :aliases        []
                              :version        "1.0.0"
                              :java-runtime   {:version         :java17
                                               :type            :jre
                                               :modules-profile :anvil
                                               :extra-modules   #{"java.se"}}
                              :basis-opts     {:user :standard}
                              :aot?           false})

  (:mvn/repos
    (ab/create-basis {:user :standard}))


  (def basis (ab/create-basis {:project "deps.edn"}))

  (keys basis)

  )