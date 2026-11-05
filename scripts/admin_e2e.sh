#!/usr/bin/env bash
# Web 管理后台 E2E：10 页数据接口全量 + RBAC 权限矩阵（走 Vite 3100 代理，等同前端请求路径）
# 用法: bash scripts/admin_e2e.sh [base_url]
set -e
BASE="${1:-http://127.0.0.1:3100}"
MYSQL="D:/sofa/mysql/mysql/bin/mysql.exe"
req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
code() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' "$@"; }
pass=0; fail=0
check() { # check 名称 实际 期望
  if [ "$2" = "$3" ]; then echo "✅ $1 ($2)"; pass=$((pass+1));
  else echo "❌ $1 实际=$2 期望=$3"; fail=$((fail+1)); fi
}

echo "== 1.服务与代理 =="
check "index.html" "$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/")" "200"
check "错误密码" "$(code -X POST "$BASE/api/ops/auth/login" -d '{"username":"admin","password":"bad"}')" "401"

echo "== 2.ADMIN 登录 =="
TOKEN=$(req -X POST "$BASE/api/ops/auth/login" -d '{"username":"admin","password":"admin123"}' | python -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
check "登录成功(token非空)" "$([ -n "$TOKEN" ] && echo yes || echo no)" "yes"

echo "== 3.十页数据接口(GET 全部应200) =="
for ep in "/api/ops/summary" "/api/ops/users?page=1&size=20" "/api/ops/orders?page=1&size=20" \
          "/api/ops/questions?page=1&size=20" "/api/ops/alarms" "/api/ops/config" \
          "/api/ops/review/tickets?status=1" "/api/ops/tickets?status=0" "/api/ops/auth/me"; do
  check "GET $ep" "$(code "$BASE$ep" -H "Authorization: Bearer $TOKEN")" "200"
done
echo "== 4.工作台数据抽样 =="
req "$BASE/api/ops/summary" -H "Authorization: Bearer $TOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)['data']
print('注册家庭:', d['families'], '| 订单:', d['ordersPaid'], '| 收入(分):', d['revenueTotal'], '| 未读告警:', d['alarmsUnread'])"

echo "== 5.写操作(ADMIN) =="
check "PUT 价格配置" "$(code -X PUT "$BASE/api/ops/config/sku.month.price" -H "Authorization: Bearer $TOKEN" -d '{"value":"900"}')" "200"
check "POST 审核裁决(不存在的工单应400而非403)" "$(code -X POST "$BASE/api/ops/review/tickets/999999/decide" -H "Authorization: Bearer $TOKEN" -d '{"pass":true,"reason":""}')" "400"
check "POST 申诉裁决(不存在的工单应400而非403)" "$(code -X POST "$BASE/api/ops/tickets/999999/decide" -H "Authorization: Bearer $TOKEN" -d '{"approve":false,"decision":"x"}')" "400"

echo "== 6.RBAC 权限矩阵(5 角色 × 3 写域) =="
"$MYSQL" -uroot -p"root123!@#" --default-character-set=utf8mb4 -e "
USE xuexijiesuo;
DELETE FROM admin_user WHERE username IN ('fin','cs','rev','view','ops');
INSERT INTO admin_user(username,password_hash,role) VALUES
 ('fin','PLAIN:test123','FINANCE'),('cs','PLAIN:test123','CS'),
 ('rev','PLAIN:test123','REVIEWER'),('view','PLAIN:test123','VIEWER'),('ops','PLAIN:test123','OPS');" 2>/dev/null
declare -A EXPECT_CONFIG=( [ADMIN]=200 [FINANCE]=200 [REVIEWER]=403 [CS]=403 [VIEWER]=403 [OPS]=403 )
declare -A EXPECT_REVIEW=( [ADMIN]=400 [FINANCE]=403 [REVIEWER]=400 [CS]=403 [VIEWER]=403 [OPS]=403 )
declare -A EXPECT_TICKET=( [ADMIN]=400 [FINANCE]=403 [REVIEWER]=403 [CS]=400 [VIEWER]=403 [OPS]=403 )
for u in admin fin cs rev view ops; do
  T=$(req -X POST "$BASE/api/ops/auth/login" -d "{\"username\":\"$u\",\"password\":\"$( [ "$u" = admin ] && echo admin123 || echo test123 )\"}" | python -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])")
  ROLE=$(req "$BASE/api/ops/auth/me" -H "Authorization: Bearer $T" | python -c "import json,sys; print(json.load(sys.stdin)['data']['role'])")
  check "$ROLE 读取工作台" "$(code "$BASE/api/ops/summary" -H "Authorization: Bearer $T")" "200"
  check "$ROLE 改价" "$(code -X PUT "$BASE/api/ops/config/sku.month.price" -H "Authorization: Bearer $T" -d '{"value":"900"}')" "${EXPECT_CONFIG[$ROLE]}"
  check "$ROLE 审核裁决" "$(code -X POST "$BASE/api/ops/review/tickets/999999/decide" -H "Authorization: Bearer $T" -d '{"pass":true,"reason":""}')" "${EXPECT_REVIEW[$ROLE]}"
  check "$ROLE 申诉裁决" "$(code -X POST "$BASE/api/ops/tickets/999999/decide" -H "Authorization: Bearer $T" -d '{"approve":false,"decision":"x"}')" "${EXPECT_TICKET[$ROLE]}"
done

echo "== 汇总: PASS=$pass FAIL=$fail =="
[ "$fail" -eq 0 ] || exit 1
