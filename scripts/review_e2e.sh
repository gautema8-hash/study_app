#!/usr/bin/env bash
# 内容合规 E2E（W6 收尾）：灰词→人工复核→出题排除→裁决通过→异常快答判废→申诉→撤销判废
# 用法: bash scripts/review_e2e.sh [base_url]  （PHONE 换号）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13100001234}"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
code() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
sql() { "D:/sofa/mysql/mysql/bin/mysql.exe" -uroot -p"root123!@#" -e "USE xuexijiesuo; $1" 2>/dev/null; }

echo "== 1.家长登录+建3题(1题含灰词'免费领取') =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$(echo "$SEND" | jqv "['data']['devCode']")\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")
req -X POST "$BASE/api/bank/questions/import" -H "Authorization: Bearer $PTOKEN" \
  -d '{"items":[{"subject":2,"grade":8,"difficulty":3,"qType":1,"stem":"合规题1","options":["A. 对","B. 错"],"answer":"A","analysis":"x"},{"subject":2,"grade":8,"difficulty":3,"qType":1,"stem":"合规题2","options":["A. 对","B. 错"],"answer":"A","analysis":"x"},{"subject":2,"grade":8,"difficulty":3,"qType":1,"stem":"扫码免费领取答案","options":["A. 对","B. 错"],"answer":"A","analysis":"x"}]}' | head -c 150; echo

echo "== 2.配置2题+学生绑定 =="
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":2,"durationMin":5,"threshold":60,"sessionMinutes":30,"dailyLimit":5}' > /dev/null
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $PTOKEN" -d '{"nickname":"合规测试","grade":8}')
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$(echo "$BC" | jqv "['data']['bindCode']")\",\"deviceFingerprint\":\"fp-review-e2e-$RANDOM\",\"deviceName\":\"合规测试机\",\"osType\":1}")
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")

echo "== 3.学生出题2题(灰词题应被排除) =="
START=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
echo "$START" | python -c "
import json,sys
d=json.load(sys.stdin)['data']
stems=[q['stem'] for q in d['questions']]
print('出题:', stems)
assert not any('免费领取' in s for s in stems), '灰词题混入出题池!'
print('待审题排除OK')"

echo "== 4.admin登录 → 审核通过灰词题 =="
ALOGIN=$(req -X POST "$BASE/api/ops/auth/login" -d '{"username":"admin","password":"admin123"}')
ATOKEN=$(echo "$ALOGIN" | jqv "['data']['accessToken']")
TID=$(req "$BASE/api/ops/review/tickets?status=1" -H "Authorization: Bearer $ATOKEN" | python -c "
import json,sys
l=json.load(sys.stdin)['data']['list']
print([t['id'] for t in l if '免费领取' in t['stem']][0])")
req -X POST "$BASE/api/ops/review/tickets/$TID/decide" -H "Authorization: Bearer $ATOKEN" -d '{"pass":true,"reason":""}'; echo

echo "== 5.再出2题(通过后的题可入池) =="
START2=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
TASK2=$(echo "$START2" | jqv "['data']['taskId']")
QIDS2=$(echo "$START2" | python -c "import json,sys; print(','.join(str(q['id']) for q in json.load(sys.stdin)['data']['questions']))")
echo "task2=$TASK2 题目=$QIDS2"

echo "== 6.异常快答判废(全对但<2s) =="
A='{"taskId":'$TASK2',"answers":['
first=1
IFS=',' read -ra A2 <<< "$QIDS2"
for qid in "${A2[@]}"; do
  [ $first -eq 0 ] && A+=','
  A+="{\"questionId\":$qid,\"answer\":\"A\",\"costMs\":500,\"switchCount\":0}"; first=0
done
A+=']}'
req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$A" | head -c 180; echo

echo "== 7.学生申诉 =="
req -X POST "$BASE/api/risk/appeal" -H "Authorization: Bearer $STOKEN" -d "{\"taskId\":$TASK2,\"content\":\"网络卡顿导致秒答，非作弊\"}"; echo

echo "== 8.admin撤销判废 → 任务恢复达标 =="
TICKET=$(req "$BASE/api/ops/tickets?status=0" -H "Authorization: Bearer $ATOKEN" | python -c "
import json,sys
l=json.load(sys.stdin)['data']['list']
print([t['id'] for t in l if t['taskId']==$TASK2][0])")
req -X POST "$BASE/api/ops/tickets/$TICKET/decide" -H "Authorization: Bearer $ATOKEN" -d '{"approve":true,"decision":"证据核实"}'; echo
sql "SELECT id, result, score FROM quiz_task WHERE id=$TASK2" | tail -2
echo "== 内容合规 E2E 完成 =="
