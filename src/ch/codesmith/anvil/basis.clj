(ns ch.codesmith.anvil.basis
  (:require [clojure.tools.build.api :as b]))

(defmulti extra identity)

(defmethod extra :gitlab
  [_]
  (when (System/getenv "CI")
    {:mvn/local-repo ".m2/repository"}))

(defmethod extra :default
  [val]
  val)

(defn create-basis []
  (b/create-basis {}))
