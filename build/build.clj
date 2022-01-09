(ns build
  (:require [ch.codesmith.anvil.shell :as sh]
            [ch.codesmith.anvil.release :as rel]
            [clojure.tools.build.api :as b]))

(def lib 'io.github.codesmith-gmbh/anvil)
(def version (str "0.2." (b/git-count-revs {})))

(defn run-tests []
  (sh/sh! "./bin/kaocha"))

(defn release [_]
  (run-tests)
  (rel/git-release! {:deps-coords         lib
                     :version             version
                     :release-branch-name "master"}))
