(ns test.hello
  (:gen-class))

(defn -main [& _]
  (prn {:version-file           (slurp "/app/version.edn")
        :implementation-version (try (some-> (Class/forName "test.hello__init")
                                             (.getPackage)
                                             (.getImplementationVersion))
                                     (catch Exception e
                                       (println e)
                                       "undefined"))}))
