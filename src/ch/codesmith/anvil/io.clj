(ns ch.codesmith.anvil.io
  (:require [clojure.java.io :as io])
  (:import (java.nio.file Files OpenOption StandardOpenOption Path)))

(extend Path
  io/IOFactory
  (assoc io/default-streams-impl
    :make-input-stream (fn [^Path x opts] (io/make-input-stream
                                            (Files/newInputStream x (make-array OpenOption 0)) opts))
    :make-output-stream (fn [^Path x opts] (io/make-output-stream
                                             (Files/newOutputStream x (if (:append opts)
                                                                        (into-array OpenOption [StandardOpenOption/APPEND])
                                                                        (make-array OpenOption 0))) opts))))
