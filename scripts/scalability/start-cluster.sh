#!/usr/bin/env sh
set -eu
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17}"
cd "$repo/final-project"
sh ./gradlew :server:installCluster --offline --no-daemon
mkdir -p build/scalability
if curl --silent --fail --max-time 1 http://127.0.0.1:8080/metrics >/dev/null; then
  echo 'A cluster already listens on port 8080' >&2; exit 1
fi
SMASHPROFS_CLUSTER_SECRET=$(head -c 32 /dev/urandom | base64)
export SMASHPROFS_CLUSTER_SECRET
umask 077
printf '%s' "$SMASHPROFS_CLUSTER_SECRET" > build/scalability/.cluster-secret
nohup "$JAVA_HOME/bin/java" -Xmx512m -cp "$repo/final-project/build/cluster/*" \
  com.smashprofs.server.cluster.ClusterMain control "$repo/config/scalability/local.properties" \
  > build/scalability/control.log 2>&1 &
echo "$!" > build/scalability/control.pid
echo 'Starting cluster; inspect http://127.0.0.1:8080/metrics and build/scalability/control.log'
