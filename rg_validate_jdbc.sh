#!/usr/bin/env bash
# RealmGate 站2 + JDBC 授权存储：验证 refresh token 跨重启仍有效（PG 持久化）
# 核心：S1 签发 RT1 后不 refresh 直接杀；重启 S2，用 RT1 refresh 期望 200（证明重启后 RT 失效问题已解决）。
# 同时回归 JDBC 下 rotation / replay 五态仍全绿。
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=/f/tcy_project/RealmGate/realmgate-server/target/realmgate-server-1.0.0.jar
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
rm -f rg_jdbc_*.txt rg_jdbc_cookies.txt
COOKIE=rg_jdbc_cookies.txt
LOG=rg_jdbc_server.log
SRV=""

start_server() {
  local tag=$1
  "$JAVA" -jar "$JAR" --server.port=9000 --spring.profiles.active=dev \
    --logging.level.com.realmgate=DEBUG \
    --logging.level.org.springframework.security.oauth2.server.authorization=DEBUG \
    --logging.level.org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter=TRACE \
    --logging.level.org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider=TRACE \
    --logging.level.org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2RefreshTokenAuthenticationConverter=TRACE > "$LOG" 2>&1 &
  SRV=$!
  echo "[$tag] server pid=$SRV"
  local up=0; local i
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
decode_fam() { "$PY" -c "import sys,json,base64; t=sys.argv[1].split('.')[1]; t+='='*(-len(t)%4); print(json.loads(base64.urlsafe_b64decode(t)).get('family_id'))" "$1"; }

# ===== Phase A: S1 签发 RT1 后不 refresh 直接杀（保留 RT1 未用过）=====
echo "########## PHASE A: issue RT1 on S1, then KILL (no refresh) ##########"
start_server S1
RESP=$(curl -s -i -c "$COOKIE" "$B/oauth2/authorize?response_type=code&client_id=realmgate-web&scope=openid%20profile&redirect_uri=$REDIR&code_challenge=$CHALLENGE&code_challenge_method=S256&state=xyz")
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
RESP=$(curl -s -i -b "$COOKIE" -c "$COOKIE" -X POST --data-urlencode "tenant=alpha" --data-urlencode "username=admin" --data-urlencode "password=admin123" "$B/login")
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
RESP=$(curl -s -i -b "$COOKIE" -c "$COOKIE" "$LOC")
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
CODE_VAL=$(echo "$LOC" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
H_TOK1=$(curl -s -o rg_jdbc_tok1.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE_VAL" \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=realmgate-web" --data-urlencode "redirect_uri=$REDIR")
echo "  State1 http=$H_TOK1"
AT1=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok1.txt'))['access_token'])")
RT1=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok1.txt'))['refresh_token'])")
FAM1=$(decode_fam "$AT1")
H_API1=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT1")
check "PhaseA State1 token exchange=200" "$H_TOK1" "200"
check "PhaseA State1 AT1->/api/users=200" "$H_API1" "200"
echo "  FAM1=$FAM1 ; RT1.len=${#RT1}"
echo "[kill S1 without refreshing]"
stop_server

# ===== Phase B: 重启 S2，用 RT1 refresh（跨重启）=====
echo "########## PHASE B: restart S2, refresh RT1 ACROSS RESTART ##########"
start_server S2
H_REF_X=$(curl -s -o rg_jdbc_tokX.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT1" --data-urlencode "client_id=realmgate-web")
echo "  [CORE] cross-restart refresh RT1 http=$H_REF_X"
check "CORE: RT1 valid AFTER restart (PG persistence)" "$H_REF_X" "200"
AT_X=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tokX.txt'))['access_token'])" 2>/dev/null)
RT_X=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tokX.txt'))['refresh_token'])" 2>/dev/null)
if [ -n "$AT_X" ]; then
  FAMX=$(decode_fam "$AT_X")
  check "CORE: family unchanged after restart" "$FAMX" "$FAM1"
  H_APIX=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT_X")
  check "CORE: AT_X->/api/users=200" "$H_APIX" "200"
fi

echo "=== State2: refresh RT_X -> RT2 (same family) ==="
H_REF2=$(curl -s -o rg_jdbc_tok2.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT_X" --data-urlencode "client_id=realmgate-web")
AT2=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok2.txt'))['access_token'])" 2>/dev/null)
RT2=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok2.txt'))['refresh_token'])" 2>/dev/null)
FAM2=$(decode_fam "$AT2")
check "State2 refresh http=200" "$H_REF2" "200"
check "State2 family unchanged" "$FAM2" "$FAM1"

echo "=== State5: refresh RT2 -> RT3 (rotation) ==="
H_REF3=$(curl -s -o rg_jdbc_tok3.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT2" --data-urlencode "client_id=realmgate-web")
AT3=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok3.txt'))['access_token'])" 2>/dev/null)
RT3=$("$PY" -c "import json;print(json.load(open('rg_jdbc_tok3.txt'))['refresh_token'])" 2>/dev/null)
FAM3=$(decode_fam "$AT3")
check "State5 refresh(RT2) http=200" "$H_REF3" "200"
check "State5 family unchanged" "$FAM3" "$FAM1"
H_APIXB=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT_X")
check "State5 AT_X still valid (family alive)" "$H_APIXB" "200"

echo "=== State3: replay RT_X (used in CORE refresh) -> 401 ==="
H_REPLAY=$(curl -s -o rg_jdbc_replay.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT_X" --data-urlencode "client_id=realmgate-web")
echo "  replay body=$(cat rg_jdbc_replay.txt 2>/dev/null)"
check "State3 replay RT_X=401" "$H_REPLAY" "401"

echo "=== State4: AT_X now 401 (family revoked) ==="
H_APIXC=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT_X")
check "State4 AT_X revoked (family)" "$H_APIXC" "401"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (JDBC persistence + rotation + replay)"; else echo "SOME FAILED"; fi

echo "=== DIAGNOSTICS (Flyway V10 + column types + errors) ==="
grep -niE "Flyway|V10|oauth2_authorization|column.*type|invalid_grant|Bad value|PSQLException|JdbcOAuth2AuthorizationService" "$LOG" | head -30
