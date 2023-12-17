(ns stan
  (:require [ch.codesmith.anvil.apps :as apps]
            [ch.codesmith.anvil.basis :as ab]
            [hato.client :as hc]))

(defn init-url [repository]
  (str "https://hub.docker.com/v2/repositories/library/" repository "/tags?page_size=100"))

(defn latest-images-search [repository]
  (let [step (fn [url]
               (-> url
                 (hc/get {:as :json})
                 :body))
        tags (into []
               (comp
                 cat
                 (keep :name))
               (iteration
                 step
                 :vf :results
                 :kf :next
                 :initk (init-url repository)))]
    (fn [pattern]
      (first (filter
               #(re-find pattern %)
               tags)))))

(defn search-by-pattern [repository search patterns]
  (into {}
    (map (fn [[java-version pattern]]
           [java-version (str repository ":" (search pattern))]))
    patterns))

(defn latest-images-by-pattern [repository patterns]
  (let [search (latest-images-search repository)]
    (search-by-pattern repository search patterns)))

(def jdk-temurin-image-pattern
  {:java8  #"^8u.*-jdk-jammy"
   :java11 #"^11\..*-jdk-jammy"
   :java17 #"^17\..*-jdk-jammy"
   :java18 #"^18\..*-jdk-jammy"
   :java19 #"^19\..*-jdk-jammy"
   :java20 #"^20\..*-jdk-jammy"
   :java21 #"^21\..*-jdk-jammy"
   })

(defn latest-jdk-eclipse-temurin [eclipse-temurin-search]
  (search-by-pattern "eclipse-temurin" eclipse-temurin-search jdk-temurin-image-pattern))

(def jre-temurin-image-pattern
  {:java8  #"^8u.*-jre-jammy"
   :java11 #"^11\..*-jre-jammy"
   :java17 #"^17\..*-jre-jammy"
   :java21 #"^21\..*-jre-jammy"
   })

(defn latest-jre-eclipse-temurin [eclipse-temurin-search]
  (search-by-pattern "eclipse-temurin" eclipse-temurin-search jre-temurin-image-pattern))

(defn latest-eclipse-temurin []
  (let [search (latest-images-search "eclipse-temurin")]
    {:jdk (latest-jdk-eclipse-temurin search)
     :jre (latest-jre-eclipse-temurin search)}))

(def ubuntu-image-pattern
  {:jammy #"jammy-*"})

(defn latest-ubuntu []
  (latest-images-by-pattern "ubuntu" ubuntu-image-pattern))

(comment

  (latest-eclipse-temurin)
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
    (hc/get "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags?page_size=100"
      {:as :json}))

  (:tag_last_pushed (first (:results (:body images))))

  (into []
    (comp
      (filter :name)
      (filter #(re-find (:name %)))
      (map #(select-keys % [:name :tag_last_pushed])))
    (:results (:body images)))

  )