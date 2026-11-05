#!/usr/bin/env bash
# RealmGate 站2 五态验证：Refresh Token Rotation + Token Family + Replay Detection
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=/f/tcy_project/RealmGate/realmgate-server/target/realmgate-server-1.0.0.jar
LOG=rg_server.log
COOKIE=rg_cookies.txt
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
rm -f "$COOKIE" rg_*.txt

echo "=== starting server (jar) ==="
"$JAVA" -jar "$JAR" --server.port=9000 --spring.profiles.active=dev \
  --logging.level.com.realmgate=DEBUG \
  --logging.level.org.springframework.security.oauth2.server.authorization=DEBUG \
  --logging.level.org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter=TRACE \
  --logging.level.org.springframework.security.oauth2.server.authorization.authentication.OAuth2RefreshTokenAuthenticationProvider=TRACE \
  --logging.level.org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2RefreshTokenAuthenticationConverter=TRACE > "$LOG" 2>&1 &
SRV=$!
trap 'kill -9 $SRV 2>/dev/null' EXIT
echo "server pid=$SRV"

UP=0
for i in $(seq 1 90); do
  if (exec 3<>/dev/tcp/127.0.0.1/9000) 2>/dev/null; then UP=1; break; fi
  sleep 1
done
if [ "$UP" -ne 1 ]; then echo "SERVER DID NOT START"; tail -40 "$LOG"; exit 1; fi
echo "server up after ${i}s"

# ---------- PKCE ----------
VERIFIER=$("$PY" -c "import secrets,base64; print(base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b'=').decode())")
CHALLENGE=$("$PY" -c "import hashlib,base64,sys; v=sys.argv[1].encode(); print(base64.urlsafe_b64encode(hashlib.sha256(v).digest()).rstrip(b'=').decode())" "$VERIFIER")

decode_fam() { "$PY" -c "import sys,json,base64; t=sys.argv[1].split('.')[1]; t+='='*(-len(t)%4); print(json.loads(base64.urlsafe_b64decode(t)).get('family_id'))" "$1"; }

PASS=0; FAIL=0
check() { if [ "$2" = "$3" ]; then echo "  [PASS] $1 (got=$2 expect=$3)"; PASS=$((PASS+1)); else echo "  [FAIL] $1 (got=$2 expect=$3)"; FAIL=$((FAIL+1)); fi; }

echo "=== [1] authorize (expect 302 -> login) ==="
RESP=$(curl -s -i -c "$COOKIE" "$B/oauth2/authorize?response_type=code&client_id=realmgate-web&scope=openid%20profile&redirect_uri=$REDIR&code_challenge=$CHALLENGE&code_challenge_method=S256&state=xyz")
echo "$RESP" | head -1
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
echo "  location=$LOC"

echo "=== [2] login (expect 302 -> authorize, session now authed) ==="
RESP=$(curl -s -i -b "$COOKIE" -c "$COOKIE" -X POST --data-urlencode "tenant=alpha" --data-urlencode "username=admin" --data-urlencode "password=admin123" "$B/login")
echo "$RESP" | head -1
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
echo "  login location=$LOC"
echo "=== [2b] follow authorize (authed) to get code (302 -> callback?code=) ==="
RESP=$(curl -s -i -b "$COOKIE" -c "$COOKIE" "$LOC")
echo "$RESP" | head -1
LOC=$(echo "$RESP" | grep -i '^location:' | tr -d '\r' | awk '{print $2}')
echo "  authorize location=$LOC"
CODE_VAL=$(echo "$LOC" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
echo "  code=${CODE_VAL:0:24}..."

echo "=== [3] token exchange (State1: AT1+RT1) ==="
H_TOK1=$(curl -s -o rg_tok1.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=authorization_code" --data-urlencode "code=$CODE_VAL" \
  --data-urlencode "code_verifier=$VERIFIER" --data-urlencode "client_id=realmgate-web" --data-urlencode "redirect_uri=$REDIR")
echo "  http=$H_TOK1"
AT1=$("$PY" -c "import json;print(json.load(open('rg_tok1.txt'))['access_token'])")
RT1=$("$PY" -c "import json;print(json.load(open('rg_tok1.txt'))['refresh_token'])")
FAM1=$(decode_fam "$AT1")
echo "  AT1.family_id=$FAM1 ; RT1.len=${#RT1}"

echo "=== [4] AT1 -> /api/users (State1: 200) ==="
H_API1=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT1")
check "State1 AT1->/api/users=200" "$H_API1" "200"

echo "=== [5] refresh RT1 -> AT2+RT2 (State2: same family, new jti) ==="
H_REF2=$(curl -s -o rg_tok2.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT1" --data-urlencode "client_id=realmgate-web")
echo "  http=$H_REF2"
AT2=$("$PY" -c "import json;print(json.load(open('rg_tok2.txt'))['access_token'])")
RT2=$("$PY" -c "import json;print(json.load(open('rg_tok2.txt'))['refresh_token'])")
FAM2=$(decode_fam "$AT2")
echo "  AT2.family_id=$FAM2"
check "State2 refresh http=200" "$H_REF2" "200"
check "State2 family unchanged" "$FAM2" "$FAM1"

echo "=== [6] refresh RT2 -> AT3+RT3 (State5: rotation works) ==="
H_REF3=$(curl -s -o rg_tok3.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT2" --data-urlencode "client_id=realmgate-web")
echo "  http=$H_REF3"
AT3=$("$PY" -c "import json;print(json.load(open('rg_tok3.txt'))['access_token'])")
RT3=$("$PY" -c "import json;print(json.load(open('rg_tok3.txt'))['refresh_token'])")
FAM3=$(decode_fam "$AT3")
check "State5 refresh(RT2) http=200" "$H_REF3" "200"
check "State5 family unchanged after 2 rotations" "$FAM3" "$FAM1"

echo "=== [7] AT1 still 200 after rotations (State5: family NOT killed) ==="
H_API1B=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT1")
check "State5 AT1 still valid (family alive)" "$H_API1B" "200"

echo "=== [8] replay RT1 -> 401 (State3: REPLAY -> family revoked) ==="
H_REPLAY=$(curl -s -o rg_replay.txt -w '%{http_code}' -X POST "$B/oauth2/token" \
  --data-urlencode "grant_type=refresh_token" --data-urlencode "refresh_token=$RT1" --data-urlencode "client_id=realmgate-web")
echo "  http=$H_REPLAY ; body=$(cat rg_replay.txt)"
check "State3 replay RT1=401" "$H_REPLAY" "401"

echo "=== [9] AT1 now 401 (State4: family revoked) ==="
H_API1C=$(curl -s -o /dev/null -w '%{http_code}' "$B/api/users" -H "Authorization: Bearer $AT1")
check "State4 AT1 revoked (family) " "$H_API1C" "401"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
echo "FAM1=$FAM1"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN"; else echo "SOME FAILED"; fi

echo "=== SERVER LOG DIAGNOSTICS (whole log) ==="
echo "--- REPLAY filter execution ---"
grep -nE "REPLAY_" "$LOG" | head -40
echo "--- refresh-related errors/exceptions ---"
grep -niE "invalid_grant|OAuth2AuthenticationException|OAuth2RefreshTokenAuthenticationProvider|RefreshTokenReplayFilter|findByToken" "$LOG" | head -40
