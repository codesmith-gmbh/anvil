(ns ch.codesmith.anvil.libs
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(defn jar ^String [{:keys [lib
                           version
                           basis
                           root
                           target-dir
                           clean?]}]
  (let [basis     (or basis (b/create-basis {:project (str (io/file root "deps.edn"))}))
        class-dir (str (io/file target-dir "classes"))
        jar-file  (str (io/file target-dir (str (name lib) "-" version ".jar")))]
    (when clean?
      (b/delete {:path (str target-dir)}))
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
    jar-file))

