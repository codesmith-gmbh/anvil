(ns hello)

(defn -main [& args]
  (println "Hello World!")
  (println (slurp "/app/version.edn")))
