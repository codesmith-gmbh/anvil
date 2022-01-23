(ns ch.codesmith.anvil.release
  (:require [ch.codesmith.anvil.shell :as sh]
            [clojure.string :as str]
            ch.codesmith.anvil.io)
  (:import (java.util.regex Pattern)
           (java.time LocalDate)))

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
  (sh/sh! "git" "tag" "-s" tag "-m" (str "\"" message " on version: " version \")))

(defn git-commit-all! [message]
  (sh/sh! "git" "commit" "-am" message))

(defn git-push-all! []
  (sh/sh! "git" "push")
  (sh/sh! "git" "push" "--tags"))

(defn replace-in-file [file f]
  (spit file (f (slurp file))))


(defn update-changelog-file [file {:keys [version local-date]}]
  (replace-in-file file
                   (fn [text]
                     (str/replace text
                                  #"(?m)^## Unreleased(.*)$"
                                  (str "## Unreleased\n\n## " version " (" local-date ")")))))

(defmulti update-readme (fn [{:keys [artifact-type]}] artifact-type))

(defmethod update-readme :deps [{:keys [deps-coords git/tag]}]
  #(str/replace %
                (Pattern/compile
                  (str
                    "(?m)^" deps-coords " \\{:git/tag .*\\}$"))
                (str deps-coords " {:git/tag \""
                     tag "\" :git/sha \""
                     (short-sha tag) "\"}")))

(defmethod update-readme :mvn [{:keys [deps-coords version]}]
  #(str/replace %
                (Pattern/compile
                  (str
                    "(?m)^" deps-coords " \\{:mvn/version .*\\}$"))
                (str deps-coords " {:mvn/version \"" version "\"}")))

(defmethod update-readme :docker-image [{:keys [docker/tag]}]
  #(str/replace %
                #"(?m)^```bash\ndocker pull.*\n```\n"
                (str "```bash\ndocker pull " tag "\n```\n")))

(defn default-update-for-release [data]
  (replace-in-file "README.md"
                   (update-readme data)))

(defn git-release! [{:keys [deps-coords version release-branch-name artifact-type
                            update-for-release]
                     :or   {update-for-release default-update-for-release
                            artifact-type      :deps}
                     :as   data}]
  (check-released-allowed release-branch-name)
  (let [tag (str "v" version)]
    (update-changelog-file "CHANGELOG.md" {:version    version
                                           :local-date (LocalDate/now)})
    (git-commit-all! (str "CHANGELOG.md release " version))
    (git-tag-version! tag version (str "Release " version " for " deps-coords))
    (update-for-release {:deps-coords   deps-coords
                         :git/tag       tag
                         :docker/tag    (:docker/tag data)
                         :version       version
                         :artifact-type artifact-type})
    (git-commit-all! "Update for release")
    (git-push-all!)))