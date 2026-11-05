#!/usr/bin/env bash
# RealmGate 站③ Revocation/Logout 标准化验证
# 1) discovery 暴露 revocation_endpoint + end_session_endpoint
# 2) id_token 含 sid claim
# 3) RFC 7009 revoke AT  -> 200，之后 AT->/api/users=401
# 4) RFC 7009 revoke RT  -> 200，之后 RT refresh=401（整族失效）
# 5) OP logout (end_session_endpoint) -> 302 回 post_logout_redirect_uri，之后 AT->/api/users=401
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=/f/tcy_project/RealmGate/realmgate-server/target/realmgate-server-1.0.0.jar
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
rm -f rg_std_*.txt rg_std_cookies.txt
COOKIE=rg_std_cookies.txt
LOG=rg_std_server.log
SRV=""

start_server() {
  local tag=$1
  "$JAVA" -jar "$JAR" --server.port=9000 --spring.profiles.active=dev \
    --logging.level.com.realmgate=DEBUG > "$LOG" 2>&1 &
  SRV=$!
  echo "[$tag] server pid=$SRV"
  local up=0 i
  for i in $(seq 1 90); do
    if (exec 3<>/dev/tcp/127.0.0.1/9000) 2>/dev/null; then up=1; break; fi
    sleep 1
  done
  if [ "$up" -ne 1 ]; then echo "[$tag] SERVER DID NOT START"; tail -50 "$LOG"; exit 1; fi
  echo "[$tag] server up after ${i}s"
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
decode_claim() { "$PY" -c "import sys,json,base64; t=sys.argv[1].split('.')[1]; t+='='*(-len(t)%4); print(json.loads(base64.urlsafe_b64decode(t)).get(sys.argv[2]) or '')" "$1" "$2"; }

# 跑完整 PKCE 流程，结果写入全局 AT/RT/ID
issue() {
  curl -s -i -c "$COOKIE" "$B/oauth2/authorize?response_type=code&client_id=realmgate-web&scope=openid%20profile&redirect_uri=$REDIR&code_challenge=$CHALLENGE&code_challenge_method=S256&state=xyz" > /tmp/rg_std_auth.txt
  LOC=$(grep -i '^location:' /tmp/rg_std_auth.txt | tr -d '\r' | awk '{print $2}')
  curl -s -i -b "$COOKIE" -c "$COOKIE" -X POST --data-urlencode "tenant=alpha" --data-urlencode "username=admin" --data-urlencode "password=admin123" "$B/login" > /tmp/rg_std_login.txt
  LOC=$(grep -i '^location:' /tmp/rg_std_login.txt | tr -d '\r' | awk '{print $2}')
  curl -s -i -b "$COOKIE" -c "$COOKIE" "$LOC" > /tmp/rg_std_cb.txt
  LOC=$(grep -i '^location:' /tmp/rg_std_cb.txt | tr -d '\r' | awk '{print $2}')
  CODE_VAL=$(echo "$LOC" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  curl -s -o rg_std_tok.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
    --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE_VAL" \
    --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=realmgate-web" --data-urlencode "redirect_uri=$REDIR" > /tmp/rg_std_code.txt
  AT=$("$PY" -c "import json;print(json.load(open('rg_std_tok.txt'))['access_token'])")
  RT=$("$PY" -c "import json;print(json.load(open('rg_std_tok.txt'))['refresh_token'])")
  ID=$("$PY" -c "import json;print(json.load(open('rg_std_tok.txt')).get('id_token',''))")
}

echo "########## START SERVER ##########"
start_server S

echo "########## 1) discovery 暴露标准端点 ##########"
DISCOVERY=$(curl -s "$B/.well-known/openid-configuration")
echo "$DISCOVERY" | grep -q '"revocation_endpoint"' && REOK=revocation_endpoint || REOK=""
echo "$DISCOVERY" | grep -q '"end_session_endpoint"' && ESOK=end_session_endpoint || ESOK=""
check "discovery has revocation_endpoint" "$REOK" "revocation_endpoint"
check "discovery has end_session_endpoint" "$ESOK" "end_session_endpoint"

echo "########## 2) id_token 含 sid claim ##########"
issue
SID1=$(decode_claim "$ID" sid)
FAM1=$(decode_claim "$ID" family_id)
check "id_token sid non-empty" "${SID1:-EMPTY}" "$FAM1"

echo "########## 3) RFC 7009 revoke AT ##########"
H_REV_AT=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/oauth2/revoke" --data-urlencode "token=$AT")
check "RFC7009 revoke AT=200" "$H_REV_AT" "200"
H_API_AT=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT")
check "revoked AT -> /api/users=401" "$H_API_AT" "401"

echo "########## 4) RFC 7009 revoke RT ##########"
issue
H_REV_RT=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/oauth2/revoke" --data-urlencode "token=$RT" --data-urlencode "token_type_hint=refresh_token")
check "RFC7009 revoke RT=200" "$H_REV_RT" "200"
H_REF_RT=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT" --data-urlencode "client_id=realmgate-web")
check "revoked RT refresh=401 (family revoked)" "$H_REF_RT" "401"

echo "########## 5) OP logout (end_session_endpoint) ##########"
issue
H_LOGOUT=$(curl -s -o /dev/null -w '%{http_code}' "$B/oauth2/logout?id_token_hint=$ID&post_logout_redirect_uri=http://localhost:5173&state=xyz")
LOC_LOGOUT=$(curl -s -i "$B/oauth2/logout?id_token_hint=$ID&post_logout_redirect_uri=http://localhost:5173&state=xyz" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
check "OP logout=302" "$H_LOGOUT" "302"
check "logout Location -> post_logout_redirect_uri" "$(echo "$LOC_LOGOUT" | grep -o 'localhost:5173' | head -1)" "localhost:5173"
H_API_AT3=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT")
check "logout AT -> /api/users=401 (family revoked)" "$H_API_AT3" "401"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (RFC 7009 revocation + OP logout + sid + discovery)"; else echo "SOME FAILED"; fi
