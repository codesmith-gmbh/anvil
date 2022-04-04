(ns ch.codesmith.anvil.release
  (:require [ch.codesmith.anvil.apps :as aa]
            [ch.codesmith.anvil.io]
            [ch.codesmith.anvil.shell :as sh]
            [clojure.string :as str])
  (:import (java.time LocalDate)))

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

(defn git-version-tag [version]
  (str "v" version))

(defn git-tag-version! [version message]
  (sh/sh! "git" "tag" "-s" (git-version-tag version) "-m" (str "\"" message " on version: " version \")))

(defn git-commit-all! [message]
  (when-not (not (git-clean?))
    (sh/sh! "git" "commit" "-am" message)))

(defn git-push-all! []
  (sh/sh! "git" "push")
  (sh/sh! "git" "push" "--tags"))

(defn replace-in-file [file f]
  (spit file (f (slurp file))))

(defn fcoalesce [f a1 a2]
  (or (f a1)
      (f a2)))

(defn update-changelog-file [file {:keys [version local-date]}]
  (replace-in-file file
                   (fn [text]
                     (str/replace text
                                  #"(?m)^## Unreleased(.*)$"
                                  (str "## Unreleased\n\n## " version " (" local-date ")")))))

(defmulti dependency-line (fn [data artifact] (or (:artifact-type artifact) :deps)))

(defmethod dependency-line :deps [data {:keys [deps-coords] :as artifact}]
  (let [tag (git-version-tag (fcoalesce :version artifact data))]
    (str deps-coords " {:git/tag \""
         tag "\" :git/sha \""
         (short-sha tag) "\"}")))

(defmethod dependency-line :mvn [data {:keys [deps-coords] :as artifact}]
  (str deps-coords " {:mvn/version \"" (fcoalesce :version artifact data) "\"}"))

(defmethod dependency-line :docker-image [data {:keys [deps-coords] :as artifact}]
  (let [docker-tag (aa/app-docker-tag (fcoalesce :docker-registry artifact data)
                                      deps-coords
                                      (fcoalesce :version artifact data))]
    (str "docker pull " docker-tag)))

(defn update-readme [{:keys [artifacts] :as data}]
  (fn [content]
    (str/replace-first
      content
      #"```deps.*(?s:.*?)```"
      (str "```deps\n"
           (str/join "\n"
                     (map (partial dependency-line data)
                          artifacts))
           "\n```"))))

(defn default-update-for-release [data]
  (replace-in-file "README.md"
                   (update-readme data)))

(defn git-release! [{:keys [release-branch-name update-for-release version]
                     :or   {update-for-release default-update-for-release}
                     :as   data}]
  (check-released-allowed release-branch-name)
  (update-changelog-file "CHANGELOG.md" {:version    version
                                         :local-date (LocalDate/now)})
  (git-commit-all! (str "CHANGELOG.md release " version))
  (git-tag-version! version (str "Release " version))
  (update-for-release data)
  (git-commit-all! "Update for release")
  (git-push-all!))