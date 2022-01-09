(ns ch.codesmith.anvil.shell
  (:require [clojure.java.shell :as js]
            [clojure.string :as str])
  (:import (java.util List)))


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
