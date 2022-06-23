(ns test.hello
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defn -main [& _]
  (prn {:resource               (str (io/resource "resource.edn"))
        :version-file           (edn/read-string (slurp "/app/version.edn"))
        :implementation-version (try (some-> (Class/forName "test.hello__init")
                                             (.getPackage)
                                             (.getImplementationVersion))
                                     (catch Exception _
                                       "undefined"))}))
