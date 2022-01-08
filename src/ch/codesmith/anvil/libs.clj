(ns ch.codesmith.anvil.libs
  (:require [clojure.tools.build.api :as b]
            [clojure.java.io :as io]))

(defn jar ^String [{:keys [lib
                           version
                           basis
                           with-pom?
                           root
                           target-dir
                           clean?]}]
  (let [basis     (or basis (b/create-basis {:project (str (io/file root "deps.edn"))}))
        class-dir (str (io/file target-dir "classes"))
        src-dirs  (into []
                        (keep (fn [[lib {:keys [path-key]}]]
                                (and path-key (str (io/file root (name lib))))))
                        (:classpath basis))
        jar-file  (str (io/file target-dir (str (name lib) "-" version ".jar")))]
    (when clean?
      (b/delete {:path (str target-dir)}))
    (when with-pom?
      (b/write-pom {:class-dir class-dir
                    :lib       lib
                    :version   version
                    :basis     basis}))
    (b/copy-dir {:src-dirs   src-dirs
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file  jar-file})
    jar-file))

