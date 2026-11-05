#!/usr/bin/env bash
# 会话管控 E2E 冒烟（W4）：达标解锁→会话倒计时→心跳→风控上报→家长告警→紧急解锁→远程收回→到期锁定→fail-open
# 用法: bash scripts/control_e2e.sh [base_url]  （PHONE 换号）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13400007777}"
MYSQL="D:/sofa/mysql/mysql/bin/mysql.exe"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
code() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
sql() { "$MYSQL" -uroot -p"root123!@#" -e "USE xuexijiesuo; $1" 2>/dev/null; }

echo "== 1.家长登录+建2题+配置(1题/门槛60)+学生绑定 =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$(echo "$SEND" | jqv "['data']['devCode']")\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")
req -X POST "$BASE/api/bank/questions/import" -H "Authorization: Bearer $PTOKEN" \
  -d '{"items":[{"subject":2,"grade":8,"difficulty":2,"qType":1,"stem":"管控测试题: 2+2=? ","options":["A. 4","B. 5"],"answer":"A","analysis":"4"}]}' > /dev/null
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":1,"durationMin":3,"threshold":60,"sessionMinutes":60,"dailyLimit":5}' > /dev/null
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $PTOKEN" -d '{"nickname":"管控测试","grade":8}')
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$(echo "$BC" | jqv "['data']['bindCode']")\",\"deviceFingerprint\":\"fp-ctrl-e2e-$RANDOM\",\"deviceName\":\"管控测试机\",\"osType\":1}")
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")
PROFILEID=$(echo "$BIND" | jqv "['data']['profileId']")
echo "学生 profileId=$PROFILEID"

echo "== 2.答题达标 =="
START=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
TASKID=$(echo "$START" | jqv "['data']['taskId']")
QID=$(echo "$START" | python -c "import json,sys; print(json.load(sys.stdin)['data']['questions'][0]['id'])")
req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" \
  -d "{\"taskId\":$TASKID,\"answers\":[{\"questionId\":$QID,\"answer\":\"A\",\"costMs\":60000,\"switchCount\":0}]}" | head -c 120; echo

echo "== 3.未达标防护: 直接解锁应 400 =="
sql "UPDATE quiz_task SET result=2 WHERE id=$TASKID"  # 模拟未达标
code -X POST "$BASE/api/control/session/start" -H "Authorization: Bearer $STOKEN" | xargs echo "HTTP (预期400):"
sql "UPDATE quiz_task SET result=1 WHERE id=$TASKID"  # 恢复达标

echo "== 4.解锁会话 =="
SESS=$(req -X POST "$BASE/api/control/session/start" -H "Authorization: Bearer $STOKEN")
echo "$SESS"
SID=$(echo "$SESS" | jqv "['data']['sessionId']")

echo "== 5.会话倒计时(服务端时间) =="
req "$BASE/api/control/session/current" -H "Authorization: Bearer $STOKEN"; echo

echo "== 6.心跳(更新在线窗口+下发fail-open阈值) =="
req -X POST "$BASE/api/control/heartbeat" -H "Authorization: Bearer $STOKEN" -d '{"deviceFingerprint":"fp-ctrl-e2e-test"}'; echo

echo "== 7.风控上报T-01(卸载尝试) + 家长告警列表 =="
req -X POST "$BASE/api/risk/report" -H "Authorization: Bearer $STOKEN" -d '{"type":"T-01","detail":"检测到卸载尝试(测试注入)","profileId":'$PROFILEID'}'; echo
req "$BASE/api/alarm/list" -H "Authorization: Bearer $PTOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)['data']['list']
print('告警条数:', len(d), '| 最新:', d[0]['type'] if d else '-')"

echo "== 8.远程收回 → 会话锁定 =="
req -X POST "$BASE/api/control/parent/revoke" -H "Authorization: Bearer $PTOKEN" -d "{\"profileId\":$PROFILEID}"; echo
req "$BASE/api/control/session/current" -H "Authorization: Bearer $STOKEN"; echo

echo "== 9.家长紧急解锁(密码123456) =="
HAS=$(req "$BASE/api/auth/password/parent/status" -H "Authorization: Bearer $PTOKEN" | python -c "import json,sys; print('true' if json.load(sys.stdin)['data']['hasPassword'] else 'false')")
if [ "$HAS" = "true" ]; then
  req -X POST "$BASE/api/auth/password/parent" -H "Authorization: Bearer $PTOKEN" -d '{"password":"123456","oldPassword":"123456"}' > /dev/null
else
  req -X POST "$BASE/api/auth/password/parent" -H "Authorization: Bearer $PTOKEN" -d '{"password":"123456"}' > /dev/null
fi
req -X POST "$BASE/api/control/parent/unlock" -H "Authorization: Bearer $PTOKEN" -d "{\"profileId\":$PROFILEID,\"password\":\"123456\"}"; echo
echo "错误密码应403: $(code -X POST "$BASE/api/control/parent/unlock" -H "Authorization: Bearer $PTOKEN" -d "{\"profileId\":$PROFILEID,\"password\":\"999999\"}")"

echo "== 10.到期自动锁定(回迁deadline到过去) =="
sql "UPDATE unlock_session SET deadline_at=NOW()-INTERVAL 1 MINUTE WHERE state=5 AND profile_id=$PROFILEID"
req "$BASE/api/control/session/current" -H "Authorization: Bearer $STOKEN"; echo

echo "== 11.fail-open: 心跳回迁3小时前 → 应临时开放 =="
sql "UPDATE device_bind SET last_heartbeat_at=NOW()-INTERVAL 3 HOUR WHERE device_name='管控测试机'"
req "$BASE/api/control/failopen?deviceFingerprint=fp-ctrl-e2e-test"; echo
echo "== W4 E2E 完成 =="
