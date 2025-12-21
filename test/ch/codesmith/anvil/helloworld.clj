(ns ch.codesmith.anvil.helloworld
  (:require [babashka.fs :as fs]
            [clojure.tools.build.api :as b]))

(def lib 'ch.codesmith-test/hello)
(def version (str "0.2." (b/git-count-revs {})))
(def root-dir (fs/path "test-app" "helloworld"))

(def base-properties
  {:lib     lib
   :version version})

(defmacro with-root-dir [& body]
  `(b/with-project-root (str root-dir)
     ~@body))
