(ns ch.codesmith.anvil.release
  (:require [ch.codesmith.anvil.shell :as sh]
            [clojure.string :as str]
            ch.codesmith.anvil.io)
  (:import (java.util.regex Pattern)))

(defn git-clean? []
  (= (count (sh/sh "git" "status" "-s")) 0))

(defn git-release-branch? [release-branch-name]
  (= (sh/sh "git" "symbolic-ref" "--short" "-q" "HEAD") release-branch-name))

(defn git-commit-count []
  (Integer/parseInt (sh/sh "git" "rev-list" "--count" "HEAD")))

(defn short-sha [name]
  (sh/sh "git" "rev-parse" "--short" name))

(defn check-released-allowed [release-branch-name]
  (when-not (git-clean?)
    (throw (ex-info "Git is not clean" {})))
  (when-not (git-release-branch? release-branch-name)
    (throw (ex-info (str "Git is not on the release branch: " release-branch-name) {:release-branch-name release-branch-name}))))

(defn git-tag-version! [tag version message]
  (sh/sh! "git" "tag" "-am" (str "\"" message " on version: " version \") tag))

(defn git-commit-all! [message]
  (sh/sh! "git" "commit" "-am" message))

(defn git-push-all! []
  (sh/sh! "git" "push")
  (sh/sh! "git" "push" "--tags"))

(defn replace-in-file [f match replacement]
  (spit f
        (str/replace (slurp f) match replacement)))

(defn update-changelog-file [file version]
  (replace-in-file file
                   #"(?m)^## Unreleased(.*)$"
                   (str "## Unreleased\n\n## " version)))

(defmulti update-readme (fn [{:keys [:deps/manifest]}] manifest))

(defmethod update-readme :deps [{:keys [file deps-coords tag]}]
  (replace-in-file file
                   (Pattern/compile
                     (str
                       "(?m)^" deps-coords " \\{:git/tag .*\\}$"))
                   (str deps-coords " {:git/tag \""
                        tag "\" :git/sha \""
                        (short-sha tag) "\"}")))

(defmethod update-readme :mvn [{:keys [file deps-coords version]}]
  (replace-in-file file
                   (Pattern/compile
                     (str
                       "(?m)^" deps-coords " \\{:mvn/version .*\\}$"))
                   (str deps-coords " {:mvn/version \"" version "\"}")))

(defn git-release! [{:keys [deps-coords version release-branch-name deps/manifest]}]
  (check-released-allowed release-branch-name)
  (let [tag (str "v" version)]
    (update-changelog-file "CHANGELOG.md" version)
    (git-commit-all! (str "CHANGELOG.md release " version))
    (git-tag-version! tag version (str "Release " deps-coords))
    (update-readme {:file          "README.md"
                    :deps-coords   deps-coords
                    :tag           tag
                    :version       version
                    :deps/manifest (or manifest :deps)})
    (git-commit-all! "Update for release")
    (git-push-all!)))