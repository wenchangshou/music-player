#!/usr/bin/env bash
# Deploy the TIDAL device-login + Hi-Res broker to the relay server and enable it.
# Uses the same TIDAL clients the local tiddl install already uses.
# Usage: scripts/deploy_tidal_auth.sh <ssh-target> (set TIDAL_ACCOUNT_ORIGIN first)
set -euo pipefail
HOST="${1:?Provide an SSH target}"
ORIGIN="${TIDAL_ACCOUNT_ORIGIN:?Set the relay HTTPS origin}"
ORIGIN="${ORIGIN%/}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${TIDAL_PYTHON:-python3}"
STAMP=$(date +%Y%m%d-%H%M%S)

CLIENTS=$("$PY" -c 'import json
from tiddl.core.auth.client import CLIENT_ID as i, CLIENT_SECRET as s
from tiddl.core.auth.profiles import CLIENT_PROFILES as P
h = P["hires"]
print(json.dumps({"tidal_device": {"client_id": i, "client_secret": s},
                  "tidal_hires": {"client_id": h.client_id, "client_secret": h.client_secret}}))')

echo "== 备份并上传后台代码 =="
ssh "$HOST" "cd /opt/tidal-relay && cp -a server.py server.py.bak-$STAMP && cp -a tidal_auth.py tidal_auth.py.bak-$STAMP && cp -a /etc/tidal-relay/config.json /etc/tidal-relay/config.json.bak-$STAMP"
scp "$ROOT/backend/server.py" "$ROOT/backend/tidal_auth.py" "$HOST:/opt/tidal-relay/"

echo "== 写入客户端配置（经 stdin 传输，不出现在命令行） =="
printf '%s' "$CLIENTS" | ssh "$HOST" 'python3 -c "
import json, os, sys
p = \"/etc/tidal-relay/config.json\"
c = json.load(open(p)); c.update(json.load(sys.stdin))
t = p + \".tmp\"
with open(t, \"w\") as f: json.dump(c, f, indent=2, ensure_ascii=False)
st = os.stat(p); os.chmod(t, st.st_mode); os.chown(t, st.st_uid, st.st_gid); os.replace(t, p)
print(\"config keys:\", sorted(c))
"'

echo "== 重启服务 =="
ssh "$HOST" "systemctl restart tidal-relay && sleep 2 && systemctl is-active tidal-relay" || {
  echo "!! 服务未能启动，正在回滚到 $STAMP 备份"
  ssh "$HOST" "cd /opt/tidal-relay && cp -a server.py.bak-$STAMP server.py && cp -a tidal_auth.py.bak-$STAMP tidal_auth.py && cp -a /etc/tidal-relay/config.json.bak-$STAMP /etc/tidal-relay/config.json && systemctl restart tidal-relay; journalctl -u tidal-relay -n 30 --no-pager"
  exit 1
}

echo "== 验证 =="
curl -s -m 15 -X POST -H 'Content-Type: application/json' -d '{}' "$ORIGIN/auth/tidal/start" \
  | python3 -c 'import json,sys;d=json.load(sys.stdin);print("设备登录：", "OK 授权码 "+d["user_code"] if "user_code" in d else d)'
curl -s -m 15 -X POST -H 'Content-Type: application/json' -d '{"refresh_token":"invalid"}' "$ORIGIN/auth/tidal/hires"; echo
echo "（Hi-Res 接口对无效令牌返回 invalid_grant 即表示已启用）"
