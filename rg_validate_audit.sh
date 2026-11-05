#!/usr/bin/env bash
# RealmGate Phase 8 续 · Audit 审计验证
# 覆盖：① TENANT_ADMIN 触发 USER/ROLE 变更后，/api/admin/audits 能查到对应审计记录
#        （actor=admin、action/resourceType 正确、资源 id 非空）；
#        ② ?action= 过滤仅返回该动作；③ 无 audit:read 的 viewer 查询=403。
# 复用 PKCE 登录（obtain_token）。变量避开 bash 只读名 UID/PID。
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=realmgate-server/target/realmgate-server-1.0.0.jar
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
CKADMIN=rg_audit_ck_admin.txt
CKVIEWER=rg_audit_ck_viewer.txt
rm -f "$CKADMIN" "$CKVIEWER" rg_audit_*.txt
LOG=rg_audit_server.log
SRV=""

start_server() {
  "$JAVA" -jar "$JAR" --server.port=9000 --spring.profiles.active=dev > "$LOG" 2>&1 &
  SRV=$!
  echo "[start] pid=$SRV"
  local up=0 i
  for i in $(seq 1 90); do
    (exec 3<>/dev/tcp/127.0.0.1/9000) 2>/dev/null && { up=1; break; }
    sleep 1
  done
  if [ "$up" -ne 1 ]; then echo "[start] SERVER DID NOT START"; tail -50 "$LOG"; exit 1; fi
  echo "[start] port up after ${i}s; wait 6s for SAS endpoints"; sleep 6
}
stop_server() {
  if [ -n "$SRV" ]; then kill -9 "$SRV" 2>/dev/null; SRV=""; fi
  for p in $(netstat -ano 2>/dev/null | grep ':9000' | grep -i LISTEN | awk '{print $5}'); do taskkill //F //PID $p 2>/dev/null; done
  sleep 2
}
trap 'stop_server' EXIT

PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  [PASS] $1 (got=$2 expect=$3)"; PASS=$((PASS+1)); else echo "  [FAIL] $1 (got=$2 expect=$3)"; FAIL=$((FAIL+1)); fi; }

VERIFIER=$("$PY" -c "import secrets,base64; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode())")
CHALLENGE=$("$PY" -c "import hashlib,base64,sys; v=sys.argv[1].encode(); print(base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip(b'=').decode())" "$VERIFIER")
RUN=$("$PY" -c "import secrets; print(secrets.token_hex(4))")

A_TXT=rg_audit_a.txt; L_TXT=rg_audit_l.txt; C_TXT=rg_audit_c.txt; TOK_TXT=rg_audit_tok.txt; BODY_TXT=rg_audit_body.txt

obtain_token() {
  local tenant=$1 user=$2 pass=$3 ck=$4
  curl -s -i -c "$ck" "$B/oauth2/authorize?response_type=code&client_id=realmgate-web&scope=openid%20profile&redirect_uri=$REDIR&code_challenge=$CHALLENGE&code_challenge_method=S256&state=xyz" > "$A_TXT"
  local LOC
  LOC=$(grep -i '^location:' "$A_TXT" | tr -d '\r' | awk '{print $2}')
  curl -s -i -b "$ck" -c "$ck" -X POST --data-urlencode "tenant=$tenant" --data-urlencode "username=$user" --data-urlencode "password=$pass" "$B/login" > "$L_TXT"
  LOC=$(grep -i '^location:' "$L_TXT" | tr -d '\r' | awk '{print $2}')
  curl -s -i -b "$ck" -c "$ck" "$LOC" > "$C_TXT"
  LOC=$(grep -i '^location:' "$C_TXT" | tr -d '\r' | awk '{print $2}')
  local CODE_VAL
  CODE_VAL=$(echo "$LOC" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  curl -s -o "$TOK_TXT" -X POST "$B/oauth2/token" \
    --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE_VAL" \
    --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=realmgate-web" --data-urlencode "redirect_uri=$REDIR" >/dev/null
  if [ ! -s "$TOK_TXT" ]; then echo "  [obtain_token] token file empty"; return 1; fi
  "$PY" -c "import json;print(json.load(open('$TOK_TXT'))['access_token'])"
}

call() {
  local m=$1 u=$2 tk=$3 d=$4
  if [ -z "$d" ]; then
    curl -s -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk"
  else
    curl -s -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk" -H "Content-Type: application/json" --data "$d"
  fi
}
id_of() { "$PY" -c "import json; print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))"; }

start_server

echo "########## 0) 取 alpha/admin 管理令牌 ##########"
AT_ADMIN=$(obtain_token alpha admin admin123 "$CKADMIN")
echo "  admin token len=${#AT_ADMIN}"
if [ -z "$AT_ADMIN" ]; then echo "FATAL: 无法获取 admin token，终止"; exit 1; fi

echo "########## 1) 触发 USER 变更（CREATE/UPDATE/DELETE）##########"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"aud_user_$RUN\",\"email\":\"aud_$RUN@alpha.dev\",\"displayName\":\"AudUser\",\"password\":\"audpass1\",\"status\":\"ACTIVE\"}"); check "POST user=200" "$C" "200"
MYUID=$(id_of)
C=$(call PUT "$B/api/admin/users/$MYUID" "$AT_ADMIN" "{\"username\":\"aud_user_$RUN\",\"email\":\"aud_$RUN@alpha.dev\",\"displayName\":\"AudUser2\",\"password\":\"audpass1\",\"status\":\"ACTIVE\"}"); check "PUT user=200" "$C" "200"
C=$(call DELETE "$B/api/admin/users/$MYUID" "$AT_ADMIN"); check "DELETE user=200" "$C" "200"

echo "########## 2) 触发 ROLE 变更（CREATE/UPDATE/DELETE）##########"
C=$(call POST "$B/api/admin/roles" "$AT_ADMIN" "{\"roleName\":\"AUD_ROLE_$RUN\",\"description\":\"audit test\"}"); check "POST role=200" "$C" "200"
MYRID=$(id_of)
C=$(call PUT "$B/api/admin/roles/$MYRID" "$AT_ADMIN" "{\"roleName\":\"AUD_ROLE_$RUN\",\"description\":\"audit test 2\"}"); check "PUT role=200" "$C" "200"
C=$(call DELETE "$B/api/admin/roles/$MYRID" "$AT_ADMIN"); check "DELETE role=200" "$C" "200"

echo "########## 3) 查询审计日志 ##########"
C=$(call GET "$B/api/admin/audits" "$AT_ADMIN"); check "GET /api/admin/audits=200" "$C" "200"
ACTOR_OK=$("$PY" -c "import json; d=json.load(open('$BODY_TXT')).get('data') or []; print('1' if any(x.get('actor')=='admin' for x in d) else '0')")
check "审计日志 actor=admin 存在" "$ACTOR_OK" "1"
UC_OK=$("$PY" -c "import json; d=json.load(open('$BODY_TXT')).get('data') or []; print('1' if any(x.get('resourceType')=='USER' and x.get('action')=='CREATE' for x in d) else '0')")
check "审计含 USER/CREATE" "$UC_OK" "1"
RD_OK=$("$PY" -c "import json; d=json.load(open('$BODY_TXT')).get('data') or []; print('1' if any(x.get('resourceType')=='ROLE' and x.get('action')=='DELETE' for x in d) else '0')")
check "审计含 ROLE/DELETE" "$RD_OK" "1"
RID_OK=$("$PY" -c "import json; d=json.load(open('$BODY_TXT')).get('data') or []; print('1' if any(x.get('resourceType')=='ROLE' and x.get('action')=='CREATE' and x.get('resourceId') for x in d) else '0')")
check "审计 ROLE/CREATE 资源id 非空" "$RID_OK" "1"

echo "########## 4) 过滤：action=DELETE 仅返回 DELETE ##########"
C=$(call GET "$B/api/admin/audits?action=DELETE" "$AT_ADMIN"); check "GET audits?action=DELETE=200" "$C" "200"
DELONLY=$("$PY" -c "import json; d=json.load(open('$BODY_TXT')).get('data') or []; print('1' if len(d)>0 and all(x.get('action')=='DELETE' for x in d) else '0')")
check "过滤 action=DELETE 仅 DELETE" "$DELONLY" "1"

echo "########## 5) 越权：无 audit:read 用户查询=403 ##########"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"aud_viewer_$RUN\",\"email\":\"av_$RUN@alpha.dev\",\"displayName\":\"AudViewer\",\"password\":\"audvpass1\",\"status\":\"ACTIVE\"}"); check "POST viewer user=200" "$C" "200"
VID=$(id_of)
AT_VIEWER=$(obtain_token alpha aud_viewer_$RUN audvpass1 "$CKVIEWER")
C=$(call GET "$B/api/admin/audits" "$AT_VIEWER"); check "viewer GET audits=403" "$C" "403"
C=$(call DELETE "$B/api/admin/users/$VID" "$AT_ADMIN"); check "DELETE viewer user=200" "$C" "200"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (Audit 审计写切面 + 查询 + 越权 403)"; else echo "SOME FAILED"; fi
