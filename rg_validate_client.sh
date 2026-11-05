#!/usr/bin/env bash
# RealmGate Phase 8 续：Client 管理 JDBC 化验证
# 覆盖：① 客户端列表含种子 realmgate-web；② 公开客户端 CRUD（200）；
#       ③ 机密客户端创建（含密钥，hasSecret=true）；④ 越权（无 admin 角色用户）创建=403。
# 临时文件用项目目录相对路径（Git Bash curl /tmp 与 Windows python3 /tmp 解析不一致）。
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=realmgate-server/target/realmgate-server-1.0.0.jar
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
CKADMIN=rg_client_ck_admin.txt
CKVIEWER=rg_client_ck_viewer.txt
rm -f "$CKADMIN" "$CKVIEWER" rg_client_*.txt
LOG=rg_client_server.log
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

A_TXT=rg_client_a.txt; L_TXT=rg_client_l.txt; C_TXT=rg_client_c.txt; TOK_TXT=rg_client_tok.txt; BODY_TXT=rg_client_body.txt

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
code_of() { "$PY" -c "import json; print(json.load(open('$BODY_TXT')).get('code'))"; }
id_of() { "$PY" -c "import json; print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))"; }
has_client() { "$PY" -c "import json,sys; d=json.load(open('$BODY_TXT')); print('1' if any(x.get('clientId')==sys.argv[1] for x in (d.get('data') or [])) else '0')" "$1"; }

start_server

echo "########## 0) 取 alpha/admin 管理令牌 ##########"
AT_ADMIN=$(obtain_token alpha admin admin123 "$CKADMIN")
echo "  admin token len=${#AT_ADMIN}"
if [ -z "$AT_ADMIN" ]; then echo "FATAL: 无法获取 admin token，终止"; exit 1; fi

echo "########## 1) 客户端列表含种子 realmgate-web ##########"
C=$(call GET "$B/api/admin/clients" "$AT_ADMIN"); check "GET /api/admin/clients=200" "$C" "200"
HAS=$(has_client realmgate-web); check "列表含 realmgate-web" "$HAS" "1"

echo "########## 2) 公开客户端 CRUD ##########"
C=$(call POST "$B/api/admin/clients" "$AT_ADMIN" "{\"clientName\":\"Test Client $RUN\",\"clientAuthenticationMethods\":[\"none\"],\"authorizationGrantTypes\":[\"authorization_code\",\"refresh_token\"],\"redirectUris\":[\"http://127.0.0.1:5173/callback\"],\"scopes\":[\"openid\",\"profile\"],\"requireProofKey\":true}"); check "POST public client=200" "$C" "200"
MYCID=$(id_of)
C=$(call GET "$B/api/admin/clients/$MYCID" "$AT_ADMIN"); check "GET client/{id}=200" "$C" "200"
C=$(call PUT "$B/api/admin/clients/$MYCID" "$AT_ADMIN" "{\"clientName\":\"Test Client Renamed $RUN\",\"clientAuthenticationMethods\":[\"none\"],\"authorizationGrantTypes\":[\"authorization_code\",\"refresh_token\"],\"redirectUris\":[\"http://127.0.0.1:5173/callback\"],\"scopes\":[\"openid\",\"profile\"],\"requireProofKey\":true}"); check "PUT client/{id}=200" "$C" "200"

echo "########## 3) 机密客户端（含密钥）##########"
C=$(call POST "$B/api/admin/clients" "$AT_ADMIN" "{\"clientName\":\"Conf Client $RUN\",\"clientAuthenticationMethods\":[\"client_secret_basic\"],\"authorizationGrantTypes\":[\"client_credentials\"],\"scopes\":[\"openid\"],\"clientSecret\":\"secret_$RUN\",\"requireProofKey\":false}"); check "POST confidential client=200" "$C" "200"
CONFID=$(id_of)
C=$(call GET "$B/api/admin/clients/$CONFID" "$AT_ADMIN"); HSEC=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('hasSecret'))"); check "confidential hasSecret=true" "$HSEC" "True"

echo "########## 4) 越权：无 admin 角色用户 ##########"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"viewer_$RUN\",\"email\":\"viewer_$RUN@alpha.dev\",\"displayName\":\"Viewer\",\"password\":\"viewpass1\",\"status\":\"ACTIVE\"}")
MYVID=$(id_of)
AT_VIEWER=$(obtain_token alpha viewer_$RUN viewpass1 "$CKVIEWER")
C=$(call POST "$B/api/admin/clients" "$AT_VIEWER" "{\"clientName\":\"NoPerm $RUN\",\"clientAuthenticationMethods\":[\"none\"],\"authorizationGrantTypes\":[\"authorization_code\"],\"scopes\":[\"openid\"]}"); check "viewer POST client=403" "$C" "403"

echo "########## 5) 清理测试客户端 ##########"
C=$(call DELETE "$B/api/admin/clients/$MYCID" "$AT_ADMIN"); check "DELETE public client=200" "$C" "200"
C=$(call DELETE "$B/api/admin/clients/$CONFID" "$AT_ADMIN"); check "DELETE confidential client=200" "$C" "200"
C=$(call DELETE "$B/api/admin/users/$MYVID" "$AT_ADMIN"); check "DELETE viewer user=200" "$C" "200"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (Client JDBC 化 CRUD + 越权 403)"; else echo "SOME FAILED"; fi
