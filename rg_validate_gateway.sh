#!/usr/bin/env bash
# RealmGate Phase 9：Gateway 统一入口验证
# 覆盖：① PKCE 登录全链路经网关(8080)透传 /oauth2/**；② 业务 API 经网关 /api/** 透传；
#       ③ TraceId 响应头存在；④ JWT 粗校验（无效 token 经网关 /api=401）；
#       ⑤ 越权（无 admin 角色）经网关 POST client=403；⑥ 网关级限流（并发突发 429）；
#       ⑦ 最小写经网关（POST/DELETE client=200）。
# 注意：本脚本不调用 rm（避免 safe-delete 批量确认挂死）；临时文件靠 curl -o 覆盖 / : > 清空。
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
SRV_JAR=realmgate-server/target/realmgate-server-1.0.0.jar
GW_JAR=realmgate-gateway/target/realmgate-gateway-1.0.0.jar
B=http://localhost:8080            # 统一入口：网关
REDIR=http://127.0.0.1:5173/callback
CKADMIN=rg_gw_ck_admin.txt
CKVIEWER=rg_gw_ck_viewer.txt
LOG=rg_gw_server.log
GLOG=rg_gw_gw.log
SRV=""; GW=""

start_server() {
  "$JAVA" -jar "$SRV_JAR" --server.port=9000 --spring.profiles.active=dev > "$LOG" 2>&1 &
  SRV=$!; echo "[start server] pid=$SRV"
  local up=0 i
  for i in $(seq 1 90); do (exec 3<>/dev/tcp/127.0.0.1/9000) 2>/dev/null && { up=1; break; }; sleep 1; done
  if [ "$up" -ne 1 ]; then echo "[start server] FAILED"; tail -50 "$LOG"; exit 1; fi
  echo "[start server] port up after ${i}s; wait 6s for SAS"; sleep 6
}
start_gateway() {
  "$JAVA" -jar "$GW_JAR" --server.port=8080 --spring.profiles.active=dev > "$GLOG" 2>&1 &
  GW=$!; echo "[start gateway] pid=$GW"
  local up=0 i
  for i in $(seq 1 60); do (exec 3<>/dev/tcp/127.0.0.1/8080) 2>/dev/null && { up=1; break; }; sleep 1; done
  if [ "$up" -ne 1 ]; then echo "[start gateway] FAILED"; tail -50 "$GLOG"; exit 1; fi
  echo "[start gateway] port up after ${i}s"; sleep 3
}
stop_all() {
  [ -n "$GW" ] && kill -9 "$GW" 2>/dev/null; GW=""
  [ -n "$SRV" ] && kill -9 "$SRV" 2>/dev/null; SRV=""
  for p in $(netstat -ano 2>/dev/null | grep -E ':(9000|8080)' | grep -i LISTEN | awk '{print $5}'); do taskkill //F //PID $p 2>/dev/null; done
  sleep 2
}
trap 'stop_all' EXIT

PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  [PASS] $1 (got=$2 expect=$3)"; PASS=$((PASS+1)); else echo "  [FAIL] $1 (got=$2 expect=$3)"; FAIL=$((FAIL+1)); fi; }

VERIFIER=$("$PY" -c "import secrets,base64; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode())")
CHALLENGE=$("$PY" -c "import hashlib,base64,sys; v=sys.argv[1].encode(); print(base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip(b'=').decode())" "$VERIFIER")
RUN=$("$PY" -c "import secrets; print(secrets.token_hex(4))")

A_TXT=rg_gw_a.txt; L_TXT=rg_gw_l.txt; C_TXT=rg_gw_c.txt; TOK_TXT=rg_gw_tok.txt; BODY_TXT=rg_gw_body.txt

obtain_token() {
  local tenant=$1 user=$2 pass=$3 ck=$4
  curl -s --max-time 20 -i -c "$ck" "$B/oauth2/authorize?response_type=code&client_id=realmgate-web&scope=openid%20profile&redirect_uri=$REDIR&code_challenge=$CHALLENGE&code_challenge_method=S256&state=xyz" > "$A_TXT"
  local LOC; LOC=$(grep -i '^location:' "$A_TXT" | tr -d '\r' | awk '{print $2}')
  curl -s --max-time 20 -i -b "$ck" -c "$ck" -X POST --data-urlencode "tenant=$tenant" --data-urlencode "username=$user" --data-urlencode "password=$pass" "$B/login" > "$L_TXT"
  LOC=$(grep -i '^location:' "$L_TXT" | tr -d '\r' | awk '{print $2}')
  curl -s --max-time 20 -i -b "$ck" -c "$ck" "$LOC" > "$C_TXT"
  LOC=$(grep -i '^location:' "$C_TXT" | tr -d '\r' | awk '{print $2}')
  local CODE_VAL; CODE_VAL=$(echo "$LOC" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  curl -s --max-time 20 -o "$TOK_TXT" -X POST "$B/oauth2/token" \
    --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE_VAL" \
    --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=realmgate-web" --data-urlencode "redirect_uri=$REDIR" >/dev/null
  if [ ! -s "$TOK_TXT" ]; then echo "  [obtain_token] token file empty"; return 1; fi
  "$PY" -c "import json;print(json.load(open('$TOK_TXT'))['access_token'])"
}
call() {
  local m=$1 u=$2 tk=$3 d=$4
  if [ -z "$d" ]; then curl -s --max-time 20 -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk"
  else curl -s --max-time 20 -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk" -H "Content-Type: application/json" --data "$d"; fi
}
id_of() { "$PY" -c "import json; print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))"; }
has_client() { "$PY" -c "import json,sys; d=json.load(open('$BODY_TXT')); print('1' if any(x.get('clientId')==sys.argv[1] for x in (d.get('data') or [])) else '0')" "$1"; }

start_server
start_gateway

echo "########## 0) 取 alpha/admin 令牌（PKCE 经网关透传 /oauth2/**）##########"
AT_ADMIN=$(obtain_token alpha admin admin123 "$CKADMIN")
echo "  admin token len=${#AT_ADMIN}"
[ -z "$AT_ADMIN" ] && { echo "FATAL: 无法获取 admin token"; exit 1; }

echo "########## 1) 业务 API 经网关 /api/** 透传 + 种子 client ##########"
C=$(call GET "$B/api/admin/clients" "$AT_ADMIN"); check "GET /api/admin/clients(经网关)=200" "$C" "200"
HAS=$(has_client realmgate-web); check "列表含种子 realmgate-web(经网关)" "$HAS" "1"

echo "########## 2) TraceId：响应头含 X-Trace-Id ##########"
curl -s --max-time 20 -D rg_gw_hdr.txt -o rg_gw_b2.txt -w '%{http_code}' -X GET "$B/api/admin/clients" -H "Authorization: Bearer $AT_ADMIN" > rg_gw_c2.txt
CODE=$(cat rg_gw_c2.txt)
HAS_TID=$(grep -i "X-Trace-Id" rg_gw_hdr.txt >/dev/null && echo 1 || echo 0)
if [ "$CODE" = "200" ] && [ "$HAS_TID" = "1" ]; then echo "  [PASS] 200 + X-Trace-Id 存在"; PASS=$((PASS+1)); else echo "  [FAIL] code=$CODE tid=$HAS_TID"; FAIL=$((FAIL+1)); fi

echo "########## 3) JWT 粗校验：无效 token 经网关 /api = 401 ##########"
C=$(call GET "$B/api/admin/clients" "invalid.token.here"); check "无效 token 经网关 /api=401" "$C" "401"

echo "########## 4) 越权：无 admin 角色用户 经网关 POST client = 403 ##########"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"viewer_$RUN\",\"email\":\"viewer_$RUN@alpha.dev\",\"displayName\":\"Viewer\",\"password\":\"viewpass1\",\"status\":\"ACTIVE\"}")
MYVID=$(id_of)
AT_VIEWER=$(obtain_token alpha viewer_$RUN viewpass1 "$CKVIEWER")
C=$(call POST "$B/api/admin/clients" "$AT_VIEWER" "{\"clientName\":\"NoPerm $RUN\",\"clientAuthenticationMethods\":[\"none\"],\"authorizationGrantTypes\":[\"authorization_code\"],\"scopes\":[\"openid\"]}"); check "viewer 经网关 POST client=403" "$C" "403"

echo "########## 5) 网关级限流：顺序 12 次触发 429（burst 演示值=5）##########"
RATE=0
for n in $(seq 1 12); do
  C=$(call GET "$B/api/admin/clients" "$AT_ADMIN")
  [ "$C" = "429" ] && RATE=$((RATE+1))
done
if [ "$RATE" -gt 0 ]; then echo "  [PASS] 网关限流命中 429 (count=$RATE/12)"; PASS=$((PASS+1)); else echo "  [FAIL] 未触发限流 429"; FAIL=$((FAIL+1)); fi
# 令牌桶被打空后需恢复，避免影响后续写用例（replenish=1/s，sleep 10 补 10 > burst 5）
sleep 10

echo "########## 6) 最小写经网关：POST/DELETE client = 200 ##########"
C=$(call POST "$B/api/admin/clients" "$AT_ADMIN" "{\"clientName\":\"GW Test $RUN\",\"clientAuthenticationMethods\":[\"none\"],\"authorizationGrantTypes\":[\"authorization_code\",\"refresh_token\"],\"redirectUris\":[\"http://127.0.0.1:5173/callback\"],\"scopes\":[\"openid\",\"profile\"],\"requireProofKey\":true}"); check "POST client 经网关=200" "$C" "200"
MYCID=$(id_of)
C=$(call DELETE "$B/api/admin/clients/$MYCID" "$AT_ADMIN"); check "DELETE client 经网关=200" "$C" "200"

echo "########## 7) 清理 ##########"
C=$(call DELETE "$B/api/admin/users/$MYVID" "$AT_ADMIN"); check "DELETE viewer 用户=200" "$C" "200"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (Phase 9 Gateway 统一入口)"; else echo "SOME FAILED"; fi
