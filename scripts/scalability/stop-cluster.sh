#!/usr/bin/env sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
secret=$(cat "$repo/final-project/build/scalability/.cluster-secret")
curl --silent --show-error --fail --max-time 3 -X POST -H 'Content-Type: application/json' \
  -H "Authorization: $secret" -d '{}' http://127.0.0.1:8080/admin/shutdown
