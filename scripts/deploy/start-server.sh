#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ ! -f lib/server-1.0.jar || ! -f config/public.properties ]]; then
  echo 'Extract the complete server archive before starting.' >&2
  exit 1
fi
if [[ -z "${SMASHPROFS_CLUSTER_SECRET:-}" ]]; then
  SMASHPROFS_CLUSTER_SECRET="$(head -c 32 /dev/urandom | base64 | tr -d '\n')"
  export SMASHPROFS_CLUSTER_SECRET
fi
exec "${JAVA_BIN:-java}" -Xms32m -Xmx256m -cp 'lib/*' \
  com.smashprofs.server.cluster.ClusterMain standalone config/public.properties
