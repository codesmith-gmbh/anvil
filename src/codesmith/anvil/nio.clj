(ns codesmith.anvil.nio
  (:refer-clojure :exclude [resolve spit])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio.file.attribute PosixFilePermissions FileAttribute)
           (java.nio.file Files Path OpenOption StandardOpenOption)
           (java.io File FileInputStream FileOutputStream)))

(defmulti ^Path as-path class)

(defmethod as-path Path [f] f)
(defmethod as-path String [f] (Path/of f (make-array String 0)))
(defmethod as-path File [f] (.toPath f))

(extend-protocol io/Coercions
  Path
  (as-file [^Path p] (.toFile p))
  (as-url [^Path p] (.toURL (.toUri p))))

(extend Path
  io/IOFactory
  (assoc io/default-streams-impl
    :make-input-stream (fn [^Path x opts] (io/make-input-stream
                                            (Files/newInputStream x (make-array OpenOption 0)) opts))
    :make-output-stream (fn [^Path x opts] (io/make-output-stream
                                             (Files/newOutputStream x (if (:append opts)
                                                                        (into-array OpenOption [StandardOpenOption/APPEND])
                                                                        (make-array OpenOption 0))) opts))))

(defn path
  (^Path [f] (as-path f))
  (^Path [f ^String child] (.resolve (as-path f) child))
  (^Path [f ^String child & others]
   (reduce path (path f child) others)))

(defn make-executable [f]
  (let [^Path path (path f)]
    (Files/setPosixFilePermissions path
                                   (PosixFilePermissions/fromString "rwxr-xr-x"))))

(defn ensure-directory [f]
  (let [^Path path (as-path f)]
    (Files/createDirectories path (make-array FileAttribute 0))))

(defn resolve [f rel-f]
  (.resolve ^Path (as-path f) (as-path rel-f)))

(defn absolute-path [f]
  (.toAbsolutePath (as-path f)))

(defn relativize [base-f other-f]
  (.relativize (as-path base-f) (as-path other-f)))

(defn replace-in-file [f match replacement]
  (clojure.core/spit f
        (str/replace (slurp f) match replacement)))

(comment

  (replace-in-file "README.md"
                   #"(?m)^io.github.codesmith-gmbh/anvil \{:git/tag .*\}$"
                   (str "io.github.codesmith-gmbh/anvil {:git/tag \""
                        "tag" "\" :git/sha \""
                        "(short-sha tag)" "\"}"))

  )