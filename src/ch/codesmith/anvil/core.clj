(ns ch.codesmith.anvil.core
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def ^:dynamic *target* "target")

(defn target-dir
  ([] (io/file *target*))
  ([subdir]
   (io/file (target-dir) subdir)))

(defn classes-dir []
  (target-dir "classes"))

(defn clean-target-dir []
  (b/delete {:path (str (target-dir))}))
