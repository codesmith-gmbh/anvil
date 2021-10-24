(ns codesmith.anvil.shell
  (:require [clojure.string :as str]
            [clojure.java.shell :as js]))

(defn sh [& args]
  (let [{:keys [exit out] :as result} (apply js/sh args)]
    (if (= exit 0)
      (str/trim out)
      (throw (ex-info "shell error"
                      (assoc result
                        :args args))))))

(defn git-clean? []
  (= (count (sh "git" "status" "-s")) 0))

(defn git-branch []
  (sh "git" "symbolic-ref" "--short" "-q" "HEAD"))

(defn git-commit-count []
  (Integer/parseInt (sh "git" "rev-list" "--count" "HEAD")))

(defn short-sha [name]
  (subs
    (sh "git" "rev-parse" name)
    0 7))

(defn tag! [tag message]
  (sh "git" "tag" "-am" message tag))

(defn commit-all! [message]
  (sh "git" "commit" "-am" message))

(defn push-tags! []
  (sh "git" "push")
  (sh "git" "push" "--tags"))