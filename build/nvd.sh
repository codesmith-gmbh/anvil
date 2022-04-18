#!/usr/bin/env bash

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )

(
    cd "${SCRIPT_DIR}"/../nvd || exit 1
    clojure -J-Dclojure.main.report=stderr -M -m nvd.task.check "config.json" "$(cd ..; clojure -Spath)"
)