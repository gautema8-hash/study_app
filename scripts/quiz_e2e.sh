#!/usr/bin/env bash
# 题库+配置+出题+判分 E2E 冒烟（W3 收尾版，15 步）
# 覆盖：批量导入/筛选/家庭隔离/E-03题库不足/7天去重/判分达标/幂等409/回收站/DIY配置/学生脱敏/错题重练
# 用法: bash scripts/quiz_e2e.sh [base_url]  （PHONE 环境变量换号）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13900002222}"
MYSQL="D:/sofa/mysql/mysql/bin/mysql.exe"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
code() { curl -s --noproxy '*' -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }
sql() { "$MYSQL" -uroot -p"root123!@#" -e "USE xuexijiesuo; $1" 2>/dev/null; }

echo "== 1.家长登录 =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$(echo "$SEND" | jqv "['data']['devCode']")\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")

echo "== 2.批量建题5道(答案全A) =="
ITEMS='{"items":['
for i in 1 2 3 4 5; do
  ITEMS+="{\"subject\":2,\"grade\":8,\"difficulty\":3,\"qType\":1,\"stem\":\"测试题$i: 1+1=? \",\"options\":[\"A. 2\",\"B. 3\",\"C. 4\",\"D. 5\"],\"answer\":\"A\",\"analysis\":\"1+1=2\"},"
done
ITEMS=${ITEMS%,}']}'
req -X POST "$BASE/api/bank/questions/import" -H "Authorization: Bearer $PTOKEN" -d "$ITEMS" | head -c 120; echo

echo "== 3.筛选校验 =="
req "$BASE/api/bank/questions?subject=2&grade=8&difficulty=3" -H "Authorization: Bearer $PTOKEN" | python -c "import json,sys; print('total:', json.load(sys.stdin)['data']['total'])"

echo "== 4.学生设备绑定 =="
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $PTOKEN" -d '{"nickname":"答题测试","grade":8}')
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$(echo "$BC" | jqv "['data']['bindCode']")\",\"deviceFingerprint\":\"fp-quiz-e2e-$RANDOM\",\"deviceName\":\"测试机\",\"osType\":1}")
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")

echo "== 5.家庭隔离+题库不足: 配置10题但仅自建5道(跨家庭题不可见) → 400 =="
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":10,"durationMin":5,"threshold":80,"sessionMinutes":60,"dailyLimit":3}' > /dev/null
code -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN" | xargs echo "HTTP (预期400):"

echo "== 6.配置5题 → 出题成功 =="
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":5,"durationMin":5,"threshold":80,"sessionMinutes":60,"dailyLimit":3}' > /dev/null
START=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
TASKID=$(echo "$START" | jqv "['data']['taskId']")
echo "taskId=$TASKID 出题数=$(echo "$START" | python -c "import json,sys; print(len(json.load(sys.stdin)['data']['questions']))")"

echo "== 7.提交全对 → score=100 result=1 =="
QIDS=$(echo "$START" | python -c "import json,sys; print(','.join(str(q['id']) for q in json.load(sys.stdin)['data']['questions']))")
ANS='{"taskId":'$TASKID',"answers":['
first=1
IFS=',' read -ra ARR <<< "$QIDS"
for qid in "${ARR[@]}"; do
  [ $first -eq 0 ] && ANS+=','
  ANS+="{\"questionId\":$qid,\"answer\":\"A\",\"costMs\":8000,\"switchCount\":0}"; first=0
done
ANS+=']}'
req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$ANS" | head -c 150; echo

echo "== 8.重复提交 → 409 =="
code -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$ANS" | xargs echo "HTTP (预期409):"

echo "== 9.7天去重: 再出题 → 400 =="
code -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN" | xargs echo "HTTP (预期400):"

echo "== 10.回收站删除+恢复 =="
QID=$(echo "$QIDS" | cut -d, -f1)
req -X DELETE "$BASE/api/bank/questions/$QID" -H "Authorization: Bearer $PTOKEN" | head -c 80; echo
req -X POST "$BASE/api/bank/questions/$QID/restore" -H "Authorization: Bearer $PTOKEN" | head -c 80; echo

echo "== 11.时间回迁8天(清空7天去重窗口) =="
sql "UPDATE quiz_task SET created_at=NOW()-INTERVAL 8 DAY WHERE id=$TASKID; UPDATE answer_record SET created_at=NOW()-INTERVAL 8 DAY WHERE task_id=$TASKID;"

echo "== 12.DIY配置(门槛60) + 13.学生配置脱敏 =="
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":5,"durationMin":5,"threshold":60,"sessionMinutes":30,"dailyLimit":5}' | head -c 150; echo
CUR=$(req "$BASE/api/quiz/config/current" -H "Authorization: Bearer $STOKEN")
echo "$CUR"
echo "$CUR" | grep -q '"threshold":"已配置"' && echo "脱敏OK" || { echo "脱敏失败!"; exit 1; }

echo "== 14.出5题答错1题 → score=80 result=1(门槛60) =="
START2=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
TASK2=$(echo "$START2" | jqv "['data']['taskId']")
QIDS2=$(echo "$START2" | python -c "import json,sys; print(','.join(str(q['id']) for q in json.load(sys.stdin)['data']['questions']))")
IFS=',' read -ra A2 <<< "$QIDS2"
WRONGQ=${A2[0]}
A2J='{"taskId":'$TASK2',"answers":['
first=1
for qid in "${A2[@]}"; do
  [ $first -eq 0 ] && A2J+=','
  if [ "$qid" = "$WRONGQ" ]; then A2J+="{\"questionId\":$qid,\"answer\":\"Z\",\"costMs\":9000,\"switchCount\":0}"; else A2J+="{\"questionId\":$qid,\"answer\":\"A\",\"costMs\":9000,\"switchCount\":0}"; fi
  first=0
done
A2J+=']}'
req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$A2J" | head -c 200; echo

echo "== 15.错题重练: 错题回迁4天前 → 再出题应命中错题 $WRONGQ =="
sql "UPDATE answer_record SET created_at=NOW()-INTERVAL 4 DAY WHERE task_id=$TASK2 AND question_id=$WRONGQ; UPDATE quiz_task SET created_at=NOW()-INTERVAL 4 DAY WHERE id=$TASK2;"
START3=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
echo "$START3" | python -c "
import json,sys
d=json.load(sys.stdin)['data']
ids=[q['id'] for q in d['questions']]
print('重练命中:', $WRONGQ in ids, '| ids:', ids)"
echo "== E2E 完成 =="
