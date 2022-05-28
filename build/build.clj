(ns build
  (:require [ch.codesmith.anvil.release :as rel]
            [clojure.tools.build.api :as b]
            [ch.codesmith.anvil.apps :as apps]))

(def lib 'io.github.codesmith-gmbh/anvil)
(def version (str apps/anvil-epoch "." (b/git-count-revs {})))

(defn release [_]
  (rel/git-release! {:artifacts           [{:deps-coords lib}]
                     :version             version
                     :release-branch-name "master"}))
