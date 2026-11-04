#!/usr/bin/env bash
# 激励域 E2E 冒烟（W6）：达标→自动发积分(20+30=50)→打卡→满分勋章→激励中心聚合
# 用法: bash scripts/reward_e2e.sh [base_url]  （PHONE 换号）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13200009999}"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

echo "== 1.家长登录+建2道L3题+配置(2题/门槛60) =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$(echo "$SEND" | jqv "['data']['devCode']")\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")
req -X POST "$BASE/api/bank/questions/import" -H "Authorization: Bearer $PTOKEN" \
  -d '{"items":[{"subject":2,"grade":8,"difficulty":3,"qType":1,"stem":"激励题1","options":["A. 对","B. 错"],"answer":"A","analysis":"x"},{"subject":2,"grade":8,"difficulty":3,"qType":1,"stem":"激励题2","options":["A. 对","B. 错"],"answer":"A","analysis":"x"}]}' | head -c 100; echo
req -X PUT "$BASE/api/bank/config" -H "Authorization: Bearer $PTOKEN" -d '{"count":2,"durationMin":3,"threshold":60,"sessionMinutes":30,"dailyLimit":5}' > /dev/null
BC=$(req -X POST "$BASE/api/auth/student/bindcode" -H "Authorization: Bearer $PTOKEN" -d '{"nickname":"激励测试","grade":8}')
BIND=$(req -X POST "$BASE/api/auth/student/bind" -d "{\"bindCode\":\"$(echo "$BC" | jqv "['data']['bindCode']")\",\"deviceFingerprint\":\"fp-reward-e2e-$RANDOM\",\"deviceName\":\"激励测试机\",\"osType\":1}")
STOKEN=$(echo "$BIND" | jqv "['data']['accessToken']")

echo "== 2.答题全对(期望积分 = 2题×10×1.0 + 超门槛30 = 50, 满分勋章) =="
START=$(req -X POST "$BASE/api/quiz/start" -H "Authorization: Bearer $STOKEN")
TASKID=$(echo "$START" | jqv "['data']['taskId']")
QIDS=$(echo "$START" | python -c "import json,sys; print(','.join(str(q['id']) for q in json.load(sys.stdin)['data']['questions']))")
ANS='{"taskId":'$TASKID',"answers":['
first=1
IFS=',' read -ra ARR <<< "$QIDS"
for qid in "${ARR[@]}"; do
  [ $first -eq 0 ] && ANS+=','
  ANS+="{\"questionId\":$qid,\"answer\":\"A\",\"costMs\":30000,\"switchCount\":0}"; first=0
done
ANS+=']}'
SUBMIT=$(req -X POST "$BASE/api/quiz/submit" -H "Authorization: Bearer $STOKEN" -d "$ANS")
echo "$SUBMIT" | python -c "
import json,sys
d=json.load(sys.stdin)['data']
r=d.get('reward',{})
print('score:', d['score'], '| 积分:', r.get('points'), '(预期50) | streak:', r.get('streak'), '| 新勋章:', r.get('newMedals'))
assert r.get('points')==50, '积分不符'
assert 'perfect' in r.get('newMedals',[]), '满分勋章未授予'
print('发奖断言通过')"

echo "== 3.激励中心聚合(/api/reward/me) =="
ME=$(req "$BASE/api/reward/me" -H "Authorization: Bearer $STOKEN")
echo "$ME" | python -c "
import json,sys
d=json.load(sys.stdin)['data']
print('余额:', d['balance'], '(预期50) | streak:', d['streak'], '| 勋章数:', len(d['medals']), '| 今日打卡:', any(x==__import__('datetime').date.today().strftime('%Y-%m-%d') for x in d['checkins']))
assert d['balance']==50, '余额不符'
print('激励中心断言通过')"

echo "== 4.学生scope越权校验: 家长token访问激励中心应403 =="
code403=$(curl -s --noproxy '*' -o /dev/null -w '%{http_code}' "$BASE/api/reward/me" -H "Authorization: Bearer $PTOKEN")
echo "HTTP $code403 (预期403)"
echo "== W6 激励 E2E 完成 =="
