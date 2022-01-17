(ns hello)

(defn -main [& _]
  (println "Hello World!")
  (println (slurp "/app/version.edn")))
