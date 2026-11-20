#!/usr/bin/env bash
# Deploy the relay backend and the shared web remote to the relay server.
# Ships backend/server.py, accounts.py, netease_gateway.py, ai_gateway.py and the web remote (app.js, index.html), backs up the live
# copies, restarts tidal-relay and rolls back if it does not come up or the checks fail.
# Usage: TIDAL_ACCOUNT_ORIGIN=https://relay.example.com scripts/deploy_relay.sh <ssh-target>
set -euo pipefail
HOST="${1:?Provide an SSH target, e.g. root@relay.example.com}"
ORIGIN="${TIDAL_ACCOUNT_ORIGIN:?Set the relay HTTPS origin}"
ORIGIN="${ORIGIN%/}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP=$(date +%Y%m%d-%H%M%S)
DEST=/opt/tidal-relay
ASSETS=$DEST/assets

echo "== 本地测试 =="
(cd "$ROOT/backend" && python3 -m unittest -q test_server test_netease_gateway)
# The web remote is public: refuse to ship anything that looks like a hard-coded API key.
if grep -nE 'apiKey[[:space:]]*:[[:space:]]*"[^"]+"|sk-[A-Za-z0-9]{16,}|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}' \
    "$ROOT/android/app/src/main/assets/remote/src/"*.js "$ROOT/android/app/src/main/assets/remote/index.html"; then
  echo "!! Web Remote 中疑似写死了 API 密钥，已停止部署"
  exit 1
fi

echo "== 打包 Web Remote 模块 =="
(cd "$ROOT/android/app/src/main/assets/remote" && node build.js)

echo "== 检查服务器目录 =="
ssh "$HOST" "test -f $DEST/server.py && test -f $DEST/accounts.py && test -f $DEST/netease_gateway.py && test -f $ASSETS/app.js && test -f $ASSETS/index.html" || {
  echo "!! 服务器上缺少 server.py、accounts.py、netease_gateway.py、assets/app.js 或 assets/index.html，请先按 backend/README.md 完成首次部署"
  exit 1
}

FILES="$DEST/server.py $DEST/accounts.py $DEST/netease_gateway.py $DEST/ai_gateway.py $ASSETS/app.js $ASSETS/index.html"

echo "== 备份 =="
# Files added in this release (such as ai_gateway.py on its first deploy) have nothing to back up.
ssh "$HOST" "for f in $FILES; do if test -f \$f; then cp -a \$f \$f.bak-$STAMP; fi; done"

rollback() {
  echo "!! 正在回滚到 $STAMP 备份"
  ssh "$HOST" "for f in $FILES; do if test -f \$f.bak-$STAMP; then cp -a \$f.bak-$STAMP \$f; fi; done; systemctl restart tidal-relay; journalctl -u tidal-relay -n 30 --no-pager"
  exit 1
}

echo "== 上传 =="
scp "$ROOT/backend/server.py" "$ROOT/backend/accounts.py" "$ROOT/backend/netease_gateway.py" "$ROOT/backend/ai_gateway.py" "$HOST:$DEST/"
scp "$ROOT/android/app/src/main/assets/remote/index.html" "$HOST:$ASSETS/index.html"
scp "$ROOT/android/app/src/main/assets/remote/app.js" "$HOST:$ASSETS/app.js"

echo "== 重启服务 =="
ssh "$HOST" "systemctl restart tidal-relay && sleep 2 && systemctl is-active tidal-relay" || rollback

echo "== 验证 =="
# Check the relay routes and compare the served script to the release source.
health=$(curl -s -m 15 "$ORIGIN/health")
others=$(curl -s -m 15 -X POST -H 'Content-Type: application/json' -d '{}' "$ORIGIN/account/logout-others")
artwork=$(curl -s -m 15 "$ORIGIN/artwork/AAAAAAAAAAAAAAAAAAAAAAAA.jpg")
ai=$(curl -s -m 15 -o /dev/null -w "%{http_code}" -X POST -H 'Content-Type: application/json' -d '{"messages":[{"role":"user","content":"x"}]}' "$ORIGIN/api/ai")
echo "health:         $health"
echo "logout-others:  $others"
echo "artwork:        $artwork"
echo "ai (no login):  HTTP $ai"
[[ "$health" == '{"ok":true}' ]] || rollback
[[ "$others" == *请登录播放器账号* ]] || rollback
[[ "$artwork" == *封面不存在* ]] || rollback
[[ "$ai" == 401 ]] || { echo "!! 未登录时 /api/ai 应返回 401"; rollback; }
curl -fsS -m 15 "$ORIGIN/app.js" | cmp -s "$ROOT/android/app/src/main/assets/remote/app.js" - || { echo "!! 线上 app.js 与发布文件不一致"; rollback; }
echo "部署完成。备份后缀：bak-$STAMP"
