#!/usr/bin/env bash
# 认证链路 E2E 冒烟（W2）：发码→登录→refresh旋转→绑码→设备绑定→家长密码→/me
# 用法: bash scripts/auth_e2e.sh [base_url]   默认 http://127.0.0.1:8080
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHOTO="${PHONE:-13800001111}"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

echo "== 0.ping =="
req "$BASE/api/common/ping"

echo "== 1.发验证码 =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHOTO\"}")
echo "$SEND"
CODE=$(echo "$SEND" | jqv "['data']['devCode']")

echo "== 2.短信登录 =="
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHOTO\",\"code\":\"$CODE\"}")
echo "$LOGIN" | head -c 200; echo
TOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")
REFRESH=$(echo "$LOGIN" | jqv "['data']['refreshToken']")

echo "== 3.refresh旋转（旧码复用应401） =="
RF=$(req -X POST "$BASE/api/auth/token/refresh" -d "{\"refreshToken\":\"$REFRESH\"}")
TOKEN=$(echo "$RF" | jqv "['data']['accessToken']")
echo "正常旋转成功"
OLD_REUSE=$(req -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/token/refresh" -d "{\"refreshToken\":\"$REFRESH\"}")
echo "旧码复用HTTP状态: $OLD_REUSE （应为401）"

echo "== 4.创建学生档案+绑定码 =="
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $TOKEN" -d '{"nickname":"小明","grade":8}')
echo "$BC"
BINDCODE=$(echo "$BC" | jqv "['data']['bindCode']")

echo "== 5.学生设备绑定 =="
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$BINDCODE\",\"deviceFingerprint\":\"fp-xiaomi15-test01\",\"deviceName\":\"小米15\",\"osType\":1}")
echo "$BIND" | head -c 200; echo
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")
echo "学生token scope校验: $(req "$BASE/api/auth/me" -H "Authorization: Bearer $STOKEN")"

echo "== 6.家长密码设置+校验 =="
req -X POST "$BASE/api/auth/password/parent" -H "Authorization: Bearer $TOKEN" -d '{"password":"123456"}'; echo
req -X POST "$BASE/api/auth/password/parent/verify" -H "Authorization: Bearer $TOKEN" -d '{"password":"123456"}'; echo
echo "错误密码应 403: $(req -o /dev/null -w '%{http_code}' -X POST "$BASE/api/auth/password/parent/verify" -H "Authorization: Bearer $TOKEN" -d '{"password":"999999"}')"

echo "== 7./me（家长token） =="
req "$BASE/api/auth/me" -H "Authorization: Bearer $TOKEN"; echo
echo "== E2E 完成 =="
