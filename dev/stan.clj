(ns stan
  (:require
    [babashka.fs :as fs]
    [ch.codesmith.anvil.apps :as apps]
    [hato.client :as hc]
    [portal.api :as p]))

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

(def java-temurin-image-pattern
  {:jdk8  #"^8u.*-jdk-noble"
   :jdk11 #"^11\..*-jdk-noble"
   :jdk17 #"^17\..*-jdk-noble"
   :jdk1  #"^21\..*-jdk-noble"
   :jdk25 #"^25.*-jdk-noble"

   :jre8  #"^8u.*-jre-noble"
   :jre11 #"^11\..*-jre-noble"
   :jre17 #"^17\..*-jre-noble"
   :jre21 #"^21\..*-jre-noble"
   :jre25 #"^25\..*-jre-noble"
   })

(defn latest-eclipse-temurin []
  (latest-images-by-pattern "eclipse-temurin"  java-temurin-image-pattern))

(def ubuntu-image-pattern
  {:noble #"noble-*"})

(defn latest-ubuntu []
  (latest-images-by-pattern "ubuntu" ubuntu-image-pattern))

(comment

  (latest-eclipse-temurin)
  (latest-ubuntu)

  (def p (p/open {:launcher :intellij}))
  (add-tap #'p/submit)

  )