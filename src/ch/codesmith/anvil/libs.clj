(ns ch.codesmith.anvil.libs
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)))

(defn nondir-full-name
  "Creates a name separated by '--' instead of '/'; named stuff get separated"
  [& args]
  (-> (str/join "--" args)
      (str/replace "/" "--")
      (str/replace "\\" "--")
      (str/replace ":" "--")))

(defn jar-file-name [lib version]
  (nondir-full-name lib (str version ".jar")))

(defn jar ^File [lib {:keys [deps/root git/tag git/sha]} target-dir]
  (let [basis     (b/create-basis {:project (str (io/file root "deps.edn"))})
        class-dir (str (io/file target-dir "classes"))
        version   (if tag
                    (str tag "-" sha)
                    sha)
        jar-file  (str (io/file target-dir (jar-file-name lib version)))]
    (b/delete {:path (str target-dir)})
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis})
    (b/copy-dir {:src-dirs   (mapv
                               (fn [path]
                                 (str (io/file root path)))
                               (:paths basis))
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file})
    (io/file jar-file)))
