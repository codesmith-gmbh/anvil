(ns ch.codesmith.anvil.helloworld
  (:require [babashka.fs :as fs]
            [clojure.tools.build.api :as b]))

(def lib 'ch.codesmith-test/hello)
(def version (str "0.2." (b/git-count-revs {})))
(def root-dir (fs/path "test" "helloworld"))

(def base-properties
  {:lib     lib
   :version version
   :root    (str root-dir)})
