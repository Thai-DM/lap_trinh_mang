#!/usr/bin/env bash
# Database and TLS config stay outside versioned releases.
set -Eeuo pipefail
archive=${1:?release archive required}
release_id=${2:?release ID required}
domain=${3:?HTTPS domain required}
[[ "$release_id" =~ ^[a-f0-9]{40}-[0-9]+-[0-9]+$ ]] || { echo 'Invalid release ID' >&2; exit 1; }
[[ "$domain" =~ ^[a-zA-Z0-9.-]+$ ]] || { echo 'Invalid domain' >&2; exit 1; }
root=${SMASHPROFS_DEPLOY_ROOT:-/srv/smashprofs}
release="$root/releases/$release_id"
mkdir -p "$root/releases"
exec 9>"$root/deploy.lock"
flock -w 120 9
[[ ! -e "$release" ]] || { echo 'Release already exists' >&2; exit 1; }
systemctl cat smashprofs.service >/dev/null
python3 - "$archive" <<'PY'
import pathlib, sys, tarfile
with tarfile.open(sys.argv[1], 'r:gz') as archive:
    for entry in archive:
        path = pathlib.PurePosixPath(entry.name)
        if (path.is_absolute() or '..' in path.parts or not path.parts
                or path.parts[0] not in {'webapp', 'lib', 'ops'}
                or not (entry.isfile() or entry.isdir())):
            raise SystemExit('Unsafe archive entry: ' + entry.name)
PY
mkdir "$release"
tar -xzf "$archive" --no-same-owner --no-same-permissions -C "$release"
[[ -f "$release/webapp/index.html" && -f "$release/webapp/game.js" && -f "$release/lib/smashprofs-gateway.jar" && -f "$release/ops/Check-Deployment.py" ]]
python3 - "$release/webapp/version.json" "$release_id" <<'PY'
import json,sys
assert json.load(open(sys.argv[1]))['release'] == sys.argv[2], 'Wrong release metadata'
PY
chmod -R a+rX "$release"

# Adopt the manually installed version once, keeping it for first-deploy rollback.
if [[ ! -L "$root/current" ]]; then
    [[ ! -e "$root/current" && -d "$root/webapp" && ! -L "$root/webapp" && -d "$root/lib" && ! -L "$root/lib" ]]
    legacy="$root/releases/manual-$(date -u +%Y%m%dT%H%M%S)"
    mkdir "$legacy"
    mv "$root/webapp" "$legacy/webapp"
    mv "$root/lib" "$legacy/lib"
    ln -s "$legacy" "$root/current"
    ln -s current/webapp "$root/webapp"
    ln -s current/lib "$root/lib"
fi
[[ -L "$root/webapp" && -L "$root/lib" ]]
previous=$(readlink -f "$root/current")
activated=false
rollback() {
    result=${1:-$?}
    trap - ERR INT TERM
    if [[ "$activated" == true ]]; then
        echo "Deployment failed; restoring $previous" >&2
        ln -s "$previous" "$root/current.rollback-$release_id"
        mv -Tf "$root/current.rollback-$release_id" "$root/current"
        systemctl restart smashprofs.service || true
    fi
    journalctl -u smashprofs.service -n 40 --no-pager >&2 || true
    exit "$result"
}
trap rollback ERR
trap 'rollback 130' INT
trap 'rollback 143' TERM
ln -s "$release" "$root/current.next-$release_id"
mv -Tf "$root/current.next-$release_id" "$root/current"
activated=true
systemctl restart smashprofs.service
healthy=false
for attempt in {1..12}; do
    if systemctl is-active --quiet smashprofs.service && python3 "$release/ops/Check-Deployment.py" "$domain" "$release_id"; then
        healthy=true
        break
    fi
    sleep 3
done
[[ "$healthy" == true ]] || { echo 'Post-deploy health check failed' >&2; false; }
trap - ERR INT TERM
echo "Published release $release_id (previous: $previous)"
# Retain releases for manual rollback. ranking.db is never modified here.
