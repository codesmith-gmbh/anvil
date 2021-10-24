#!/usr/bin/env bb

(ns release
  (:require [clojure.string :as str]
            [clojure.java.shell :as js])
  (:import (java.util List)))

(defn version [commit-count]
  (str "0.1." commit-count))

(defn sh [& args]
  (let [{:keys [exit out] :as result} (apply js/sh args)]
    (if (= exit 0)
      (str/trim out)
      (throw (ex-info "shell error"
                      (assoc result
                        :args args))))))

(defn sh! [& args]
  "Execute the given shell command and redirect the ouput/error to the standard output error; returns nil."
  (let [^Process process (.. (ProcessBuilder. ^List args)
                             (inheritIO)
                             (start))
        exit             (.waitFor process)]
    (when (not= exit 0)
      (throw (ex-info "shell error"
                      {:exit 0
                       :args args})))))

(defn git-clean? []
  (= (count (sh "git" "status" "-s")) 0))

(defn git-main? []
  (= (sh "git" "symbolic-ref" "--short" "-q" "HEAD") "master"))

(defn git-commit-count []
  (Integer/parseInt (sh "git" "rev-list" "--count" "HEAD")))

(defn short-sha [name]
  (subs
    (sh "git" "rev-parse" name)
    0 7))

(defn check-released-allowed []
  (when-not (git-clean?)
    (throw (ex-info "Git is not clean" {})))
  (when-not (git-main?)
    (throw (ex-info "Git is not on the main branch" {}))))

(defn tag! [tag message]
  (let [commits-count (git-commit-count)
        version       (version commits-count)]
    (sh! "git" "tag" "-am" (str "\"" message " on version: " version \") tag)))

(defn commit-all! [message]
  (sh! "git" "commit" "-am" message))

(defn push-tags! []
  (sh! "git" "push")
  (sh! "git" "push" "--tags"))

(defn replace-in-file [f match replacement]
  (spit f
        (str/replace (slurp f) match replacement)))

(defn release! []
  (check-released-allowed)
  (let [version (version (git-commit-count))
        tag     (str "v" version)]
    (tag! tag "Release Anvil")
    (replace-in-file "README.md"
                     #"(?m)^io.github.codesmith-gmbh/anvil \{:git/tag .*\}$"
                     (str "io.github.codesmith-gmbh/anvil {:git/tag \""
                          tag "\" :git/sha \""
                          (short-sha tag) "\"}"))
    (replace-in-file "CHANGELOG.md"
                     #"(?m)^## Unreleased(.*)$"
                     (str "## Unreleased\n\n## " version))
    (commit-all! "Update for release")
    (push-tags!)))

(release!)