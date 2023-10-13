(ns stan
  (:require [ch.codesmith.anvil.apps :as apps]
            [ch.codesmith.anvil.basis :as ab]
            [hato.client :as hc]))

(defn latest-images-by-pattern [repository patterns]
  (let [url      (str "https://hub.docker.com/v2/repositories/library/" repository "/tags?page_size=1000")
        response (hc/get url {:as :json})
        tags     (into []
                   (keep :name)
                   (:results (:body response)))
        search   (fn [pattern]
                   (first (filter
                            #(re-find pattern %)
                            tags)))]
    (into {}
      (map (fn [[java-version pattern]]
             [java-version (str repository ":" (search pattern))]))
      patterns)))

(def jdk-temurin-image-pattern
  {:java8  #"^8u.*-jdk-jammy"
   :java11 #"^11\..*-jdk-jammy"
   :java17 #"^17\..*-jdk-jammy"
   :java18 #"^18\..*-jdk-jammy"
   :java19 #"^19\..*-jdk-jammy"
   :java20 #"^20\..*-jdk-jammy"
   :java21 #"^21_.*-jdk-jammy"
   })

(defn latest-jdk-eclipse-temurin []
  (latest-images-by-pattern "eclipse-temurin" jdk-temurin-image-pattern))

(def jre-temurin-image-pattern
  {:java8  #"^8u.*-jre-jammy"
   :java11 #"^11\..*-jre-jammy"
   :java17 #"^17\..*-jre-jammy"
   :java21 #"^21_.*-jre-jammy"
   })

(defn latest-jre-eclipse-temurin []
  (latest-images-by-pattern "eclipse-temurin" jre-temurin-image-pattern))

(def ubuntu-image-pattern
  {:jammy #"jammy-*"})

(defn latest-ubuntu []
  (latest-images-by-pattern "ubuntu" ubuntu-image-pattern))

(comment

  (latest-jdk-eclipse-temurin)
  (latest-jre-eclipse-temurin)
  (latest-ubuntu)

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

  (def images
    (hc/get "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=1000"
      {:as :json}))

  (:tag_last_pushed (first (:results (:body images))))

  (into []
    (comp
      (filter :name)
      (filter #(re-find (:name %)))
      (map #(select-keys % [:name :tag_last_pushed])))
    (:results (:body images)))

  )