(ns ch.codesmith.anvil.helloworld
  (:require [clojure.tools.build.api :as b]))

(def lib 'ch.codesmith-test/hello)
(def version (str "0.2." (b/git-count-revs {})))
(def target-dir "target")

(def base-properties
  {:lib        lib
   :version    version
   :root       "test/helloworld"
   :target-dir target-dir})
