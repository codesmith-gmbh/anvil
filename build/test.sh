#!/usr/bin/env bash

set -eo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)

(
  cd "${SCRIPT_DIR}"/.. || exit 1
  clojure -M:test:runner "$@"
)