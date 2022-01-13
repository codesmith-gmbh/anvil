(ns stan
  (:require [ch.codesmith.anvil.apps :as apps]

            [clojure.tools.build.api :as b]
            [ch.codesmith.anvil.libs :as libs]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]))

(comment

  (apps/make-docker-artifact {:main-namespace 'codesmith.anvil.artifacts
                              :lib-name       'anvic
                              :aliases        []
                              :version        "1.0.0"
                              :java-runtime   {:version           :java17
                                               :type              :jre
                                               :modules-profile   :anvil
                                               :extra-modules     #{"java.se"}}
                              :basis-opts     {:user :standard}
                              :aot?           false})

  (keys
    (b/create-basis {:user :standard}))


  (def basis (b/create-basis {:project "deps.edn"}))

  )