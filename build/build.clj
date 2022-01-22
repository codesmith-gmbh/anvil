(ns build
  (:require [ch.codesmith.anvil.shell :as sh]
            [ch.codesmith.anvil.release :as rel]
            [clojure.tools.build.api :as b]))

(def lib 'io.github.codesmith-gmbh/anvil)
(def version (str "0.4." (b/git-count-revs {})))

(defn verify []
  (sh/sh! "./build/verify"))

(defn release [_]
  (verify)
  (rel/git-release! {:deps-coords         lib
                     :version             version
                     :release-branch-name "master"}))
