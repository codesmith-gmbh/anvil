(ns codesmith.anvil.shell
  (:require [clojure.string :as str]
            [clojure.java.shell :as js])
  (:import (java.util List)))

(defn sh
  "Execute the given shell command and return the output as a string."
  [& args]
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

(defn git-branch []
  (sh "git" "symbolic-ref" "--short" "-q" "HEAD"))

(defn git-commit-count []
  (Integer/parseInt (sh "git" "rev-list" "--count" "HEAD")))

(defn short-sha [name]
  (subs
    (sh "git" "rev-parse" name)
    0 7))

(defn tag! [tag message]
  (sh! "git" "tag" "-am" message tag))

(defn commit-all! [message]
  (sh! "git" "commit" "-am" message))

(defn push-tags! []
  (sh! "git" "push")
  (sh! "git" "push" "--tags"))