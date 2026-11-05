#!/usr/bin/env bash
# RealmGate Phase 8 管理后台验证
# 覆盖：Tenant / User / Role / Permission 四件套 CRUD（均 200），以及越权(无 admin 角色的用户) 403。
# 注意：临时文件一律用「项目目录相对路径」，因 Git Bash 的 curl(/tmp) 与 Windows python3(/tmp->F:\tmp) 解析不一致。
set +e
if command -v python3 >/dev/null 2>&1; then PY=python3; elif command -v python >/dev/null 2>&1; then PY=python; else echo "NO PYTHON"; exit 2; fi

JAVA=E:/Java/JDK/java21/bin/java
JAR=realmgate-server/target/realmgate-server-1.0.0.jar
B=http://localhost:9000
REDIR=http://127.0.0.1:5173/callback
CKADMIN=rg_admin_ck_admin.txt
CKVIEWER=rg_admin_ck_viewer.txt
rm -f "$CKADMIN" "$CKVIEWER" rg_admin_*.txt
LOG=rg_admin_server.log
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
# 唯一 run 后缀：JDBC 持久化导致测试数据跨运行残留，所有测试实体加后缀保证幂等
RUN=$("$PY" -c "import secrets; print(secrets.token_hex(4))")

A_TXT=rg_admin_a.txt; L_TXT=rg_admin_l.txt; C_TXT=rg_admin_c.txt; TOK_TXT=rg_admin_tok.txt; BODY_TXT=rg_admin_body.txt

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

# $1 method $2 url $3 token $4 json-body(or empty for none)
call() {
  local m=$1 u=$2 tk=$3 d=$4
  if [ -z "$d" ]; then
    curl -s -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk"
  else
    curl -s -o "$BODY_TXT" -w '%{http_code}' -X "$m" "$u" -H "Authorization: Bearer $tk" -H "Content-Type: application/json" --data "$d"
  fi
}
# 从最近一次 list body 中按 field==value 取 id（避开 UID/PID 等 bash 只读变量）
pbid() { "$PY" -c "import json,sys; d=json.load(open('$BODY_TXT')); arr=d.get('data') or []; print(next((x['id'] for x in arr if x.get(sys.argv[1])==sys.argv[2]),''))" "$1" "$2"; }
# 取列表首个元素的 id
first_id() { "$PY" -c "import json; d=json.load(open('$BODY_TXT')); arr=d.get('data') or []; print(arr[0]['id'] if arr else '')"; }
# 取最近一次 body 的 code 字段（项目约定：业务错误 HTTP=200 + code!=0）
code_of() { "$PY" -c "import json; print(json.load(open('$BODY_TXT')).get('code'))"; }

start_server

echo "########## 0) 取 alpha/admin 管理令牌 ##########"
AT_ADMIN=$(obtain_token alpha admin admin123 "$CKADMIN")
echo "  admin token len=${#AT_ADMIN}"
if [ -z "$AT_ADMIN" ]; then echo "FATAL: 无法获取 admin token，终止"; exit 1; fi

echo "########## 1) Tenant 管理 ##########"
C=$(call GET "$B/api/admin/tenants" "$AT_ADMIN"); check "GET /api/admin/tenants=200" "$C" "200"
C=$(call POST "$B/api/admin/tenants" "$AT_ADMIN" "{\"name\":\"Test Tenant $RUN\",\"slug\":\"testtenant_$RUN\",\"status\":\"ACTIVE\"}"); check "POST tenant=200" "$C" "200"
MYTID=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
C=$(call GET "$B/api/admin/tenants/$MYTID" "$AT_ADMIN"); check "GET tenant/{id}=200" "$C" "200"
C=$(call PUT "$B/api/admin/tenants/$MYTID" "$AT_ADMIN" '{"name":"Test Tenant Renamed","status":"DISABLED"}'); check "PUT tenant/{id}=200" "$C" "200"
C=$(call POST "$B/api/admin/tenants" "$AT_ADMIN" "{\"name\":\"Dup\",\"slug\":\"testtenant_$RUN\"}"); CK=$(code_of); check "POST dup slug=code 409 (http=$C)" "$CK" "409"
C=$(call DELETE "$B/api/admin/tenants/$MYTID" "$AT_ADMIN"); check "DELETE tenant/{id}=200" "$C" "200"

echo "########## 2) User 管理 ##########"
C=$(call GET "$B/api/admin/users" "$AT_ADMIN"); check "GET /api/admin/users=200" "$C" "200"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"nobody_$RUN\",\"email\":\"nobody_$RUN@alpha.dev\",\"displayName\":\"No Body\",\"password\":\"testpass1\",\"status\":\"ACTIVE\"}"); check "POST user=200" "$C" "200"
MYUID=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
C=$(call GET "$B/api/admin/users/$MYUID" "$AT_ADMIN"); check "GET user/{id}=200" "$C" "200"
C=$(call PUT "$B/api/admin/users/$MYUID" "$AT_ADMIN" '{"displayName":"No Body II","status":"DISABLED"}'); check "PUT user/{id}=200" "$C" "200"
C=$(call POST "$B/api/admin/roles" "$AT_ADMIN" "{\"roleName\":\"TEST_ROLE_$RUN\",\"description\":\"phase8 test\"}"); check "POST role=200" "$C" "200"
MYRID=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
C=$(call POST "$B/api/admin/users/$MYUID/roles/$MYRID" "$AT_ADMIN"); check "assign role=200" "$C" "200"
C=$(call GET "$B/api/admin/users/$MYUID/roles" "$AT_ADMIN"); check "GET user roles=200" "$C" "200"
C=$(call DELETE "$B/api/admin/users/$MYUID/roles/$MYRID" "$AT_ADMIN"); check "unassign role=200" "$C" "200"
C=$(call DELETE "$B/api/admin/users/$MYUID" "$AT_ADMIN"); check "DELETE user=200" "$C" "200"

echo "########## 3) Role 管理 ##########"
C=$(call GET "$B/api/admin/roles" "$AT_ADMIN"); check "GET /api/admin/roles=200" "$C" "200"
C=$(call POST "$B/api/admin/roles" "$AT_ADMIN" "{\"roleName\":\"TEST_ROLE2_$RUN\",\"description\":\"x\"}"); check "POST role2=200" "$C" "200"
MYRID2=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
C=$(call GET "$B/api/admin/roles/$MYRID2" "$AT_ADMIN"); check "GET role/{id}=200" "$C" "200"
C=$(call PUT "$B/api/admin/roles/$MYRID2" "$AT_ADMIN" '{"description":"y"}'); check "PUT role/{id}=200" "$C" "200"
C=$(call GET "$B/api/admin/permissions" "$AT_ADMIN"); PERMID=$(first_id)
C=$(call PUT "$B/api/admin/roles/$MYRID2/permissions" "$AT_ADMIN" "[\"$PERMID\"]"); check "PUT role permissions=200" "$C" "200"
C=$(call GET "$B/api/admin/roles/$MYRID2/permissions" "$AT_ADMIN"); check "GET role permissions=200" "$C" "200"
C=$(call DELETE "$B/api/admin/roles/$MYRID2" "$AT_ADMIN"); check "DELETE role=200" "$C" "200"

echo "########## 4) Permission 管理 ##########"
C=$(call GET "$B/api/admin/permissions" "$AT_ADMIN"); check "GET /api/admin/permissions=200" "$C" "200"
C=$(call POST "$B/api/admin/permissions" "$AT_ADMIN" "{\"permKey\":\"report:read_$RUN\",\"description\":\"报表查看\"}"); check "POST permission=200" "$C" "200"
PERMID2=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
C=$(call GET "$B/api/admin/permissions/$PERMID2" "$AT_ADMIN"); check "GET permission/{id}=200" "$C" "200"
C=$(call PUT "$B/api/admin/permissions/$PERMID2" "$AT_ADMIN" '{"description":"报表查看(改)"}'); check "PUT permission/{id}=200" "$C" "200"
C=$(call DELETE "$B/api/admin/permissions/$PERMID2" "$AT_ADMIN"); check "DELETE permission=200" "$C" "200"

echo "########## 5) 越权：无 admin 角色用户 ##########"
C=$(call POST "$B/api/admin/users" "$AT_ADMIN" "{\"username\":\"viewer_$RUN\",\"email\":\"viewer_$RUN@alpha.dev\",\"displayName\":\"Viewer\",\"password\":\"viewpass1\",\"status\":\"ACTIVE\"}"); MYVID=$("$PY" -c "import json;print(json.load(open('$BODY_TXT')).get('data',{}).get('id',''))")
AT_VIEWER=$(obtain_token alpha viewer viewpass1 "$CKVIEWER")
C=$(call GET "$B/api/admin/users" "$AT_VIEWER"); check "viewer GET /api/admin/users=403" "$C" "403"
C=$(call GET "$B/api/admin/users/$MYVID" "$AT_ADMIN"); check "admin delete viewer=200" "$C" "200"

echo "=== SUMMARY ==="
echo "PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -eq 0 ]; then echo "ALL GREEN (Phase 8 四件套 CRUD + 越权 403)"; else echo "SOME FAILED"; fi
