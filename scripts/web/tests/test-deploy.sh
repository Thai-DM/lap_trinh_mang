#!/usr/bin/env bash
# Exercise the real switch/rollback script with services and TLS checks isolated.
set -Eeuo pipefail
base=$(pwd)/final-project/build/deploy-validation
mkdir -p "$base"
test_root=$(mktemp -d "$base/transaction-XXXXXX")
export TEST_ROOT="$test_root/site"
export TEST_PYTHON=${TEST_PYTHON:-$(command -v python3)}
export SMASHPROFS_DEPLOY_ROOT="$TEST_ROOT"
mkdir -p "$test_root/bin" "$TEST_ROOT/webapp" "$TEST_ROOT/lib" "$test_root/data"
printf 'manual\n' > "$TEST_ROOT/webapp/health"
printf 'database-sentinel\n' > "$test_root/data/ranking.db"
cat > "$test_root/bin/python3" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == */Check-Deployment.py ]]; then
    [[ "$(cat "$TEST_ROOT/current/webapp/health")" == ok ]]
else
    exec "$TEST_PYTHON" "$@"
fi
EOF
cat > "$test_root/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$TEST_ROOT/service-calls.log"
EOF
for name in flock sleep journalctl; do
    printf '#!/usr/bin/env bash\nexit 0\n' > "$test_root/bin/$name"
done
chmod +x "$test_root/bin/"*
export PATH="$test_root/bin:$PATH"
package() {
    python3 - "$1" "$2" "$3" <<'PY'
import io,json,sys,tarfile
with tarfile.open(sys.argv[1], 'w:gz') as archive:
    files = {'webapp/index.html':'index', 'webapp/game.js':'js',
             'webapp/version.json':json.dumps({'release':sys.argv[2]}),
             'webapp/health':sys.argv[3], 'lib/smashprofs-gateway.jar':'jar',
             'ops/Check-Deployment.py':'# isolated by test harness'}
    if sys.argv[3] == 'unsafe': files['../escape'] = 'forbidden'
    for name,text in files.items():
        data=text.encode(); entry=tarfile.TarInfo(name); entry.size=len(data)
        archive.addfile(entry,io.BytesIO(data))
PY
}
r1=1111111111111111111111111111111111111111-1-1
r2=2222222222222222222222222222222222222222-2-1
r3=3333333333333333333333333333333333333333-3-1
package "$test_root/good.tgz" "$r1" ok
bash scripts/web/Deploy-Azure.sh "$test_root/good.tgz" "$r1" localhost > "$test_root/good.log" 2>&1
[[ "$(readlink -f "$TEST_ROOT/current")" == "$TEST_ROOT/releases/$r1" ]]
[[ -L "$TEST_ROOT/webapp" && -L "$TEST_ROOT/lib" ]]
[[ "$(cat "$TEST_ROOT/webapp/health")" == ok ]]
echo 'PASS: first deploy adopts manual installation and switches both paths'
package "$test_root/bad-health.tgz" "$r2" fail
if bash scripts/web/Deploy-Azure.sh "$test_root/bad-health.tgz" "$r2" localhost > "$test_root/bad-health.log" 2>&1; then
    echo 'Unhealthy release was accepted' >&2; exit 1
fi
[[ "$(readlink -f "$TEST_ROOT/current")" == "$TEST_ROOT/releases/$r1" ]]
[[ "$(cat "$TEST_ROOT/webapp/health")" == ok ]]
[[ $(grep -c '^restart ' "$TEST_ROOT/service-calls.log") == 3 ]]
echo 'PASS: failed health check restores previous release and restarts service'
package "$test_root/unsafe.tgz" "$r3" unsafe
if bash scripts/web/Deploy-Azure.sh "$test_root/unsafe.tgz" "$r3" localhost > "$test_root/unsafe.log" 2>&1; then
    echo 'Unsafe archive was accepted' >&2; exit 1
fi
[[ ! -e "$TEST_ROOT/escape" && ! -e "$TEST_ROOT/releases/$r3" ]]
[[ "$(cat "$test_root/data/ranking.db")" == database-sentinel ]]
echo 'PASS: archive traversal rejected before extraction; database preserved'
