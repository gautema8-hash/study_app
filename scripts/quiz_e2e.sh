#!/usr/bin/env bash
# 题库+出题+判分 E2E 冒烟（W3）：家长建题→学生出题(7天去重)→作答提交→判分达标→幂等保护→判分作废
# 用法: bash scripts/quiz_e2e.sh [base_url]   依赖认证链路正常（复用 auth 流程）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13900002222}"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

echo "== 1.家长登录 =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
CODE=$(echo "$SEND" | jqv "['data']['devCode']")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$CODE\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")

echo "== 2.家长批量建题(5道,答案全A) =="
ITEMS='{"items":['
for i in 1 2 3 4 5; do
  ITEMS+="{\"subject\":2,\"grade\":8,\"difficulty\":3,\"qType\":1,\"stem\":\"测试题$i: 1+1=? \",\"options\":[\"A. 2\",\"B. 3\",\"C. 4\",\"D. 5\"],\"answer\":\"A\",\"analysis\":\"1+1=2\"},"
done
ITEMS=${ITEMS%,}']}'
IMP=$(req -X POST "$BASE/api/bank/questions/import" -H "Authorization: Bearer $PTOKEN" -d "$ITEMS")
echo "$IMP"

echo "== 3.筛选校验(数学/8年级/L3) =="
req "$BASE/api/bank/questions?subject=2&grade=8&difficulty=3" -H "Authorization: Bearer $PTOKEN" | python -c "import json,sys; d=json.load(sys.stdin)['data']; print('total:', d['total'])"

echo "== 4.生成绑定码+学生设备绑定 =="
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $PTOKEN" -d '{"nickname":"答题测试","grade":8}')
BINDCODE=$(echo "$BC" | jqv "['data']['bindCode']")
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$BINDCODE\",\"deviceFingerprint\":\"fp-quiz-e2e-$RANDOM\",\"deviceName\":\"测试机\",\"osType\":1}")
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")

echo "== 5.学生出题(10题→题库只有5道新题,应报题库不足E-03) =="
req -o /dev/null -w 'HTTP %{http_code} (预期400)\n' -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN" -d '{"count":10,"durationMin":5,"threshold":80}'

echo "== 6.出题5题 =="
START=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN" -d '{"count":5,"durationMin":5,"threshold":80}')
echo "$START" | head -c 260; echo
TASKID=$(echo "$START" | jqv "['data']['taskId']")
QIDS=$(echo "$START" | python -c "import json,sys; print(','.join(str(q['id']) for q in json.load(sys.stdin)['data']['questions']))")

echo "== 7.提交全对答案(应 score=100 result=1 达标) =="
ANS='{"taskId":'$TASKID',"answers":['
IFS=',' read -ra ARR <<< "$QIDS"
first=1
for qid in "${ARR[@]}"; do
  [ $first -eq 0 ] && ANS+=','
  ANS+="{\"questionId\":$qid,\"answer\":\"A\",\"costMs\":8000,\"switchCount\":0}"
  first=0
done
ANS+=']}'
SUBMIT=$(req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$ANS")
echo "$SUBMIT" | head -c 260; echo

echo "== 8.重复提交(应409幂等保护) =="
req -o /dev/null -w 'HTTP %{http_code} (预期409)\n' -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$ANS"

echo "== 9.出题去重(7天内不重复:5道已答完,再出应题库不足) =="
req -o /dev/null -w 'HTTP %{http_code} (预期400)\n' -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN" -d '{"count":5,"durationMin":5,"threshold":80}'

echo "== 10.回收站:家长删题+恢复 =="
QID=$(echo "$QIDS" | cut -d, -f1)
req -X DELETE "$BASE/api/bank/questions/$QID" -H "Authorization: Bearer $PTOKEN"; echo
req -X POST "$BASE/api/bank/questions/$QID/restore" -H "Authorization: Bearer $PTOKEN"; echo
echo "== 题库/判分 E2E 完成 =="
