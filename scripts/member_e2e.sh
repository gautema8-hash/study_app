#!/usr/bin/env bash
# 会员支付 E2E 冒烟（W5，资金三件套）：价格下发→下单→回调发货→幂等→补单→状态→SVIP→广告换天数→重放→退款
# 用法: bash scripts/member_e2e.sh [base_url]  （PHONE 换号）
set -e
BASE="${1:-http://127.0.0.1:8080}"
PHONE="${PHONE:-13300008888}"

req() { curl -s --noproxy '*' -H 'Content-Type: application/json' "$@"; }
jqv() { python -c "import json,sys; d=json.load(sys.stdin); print(d$1)"; }

echo "== 1.家长登录 =="
SEND=$(req -X POST "$BASE/api/auth/sms/send" -d "{\"phone\":\"$PHONE\"}")
LOGIN=$(req -X POST "$BASE/api/auth/sms/login" -d "{\"phone\":\"$PHONE\",\"code\":\"$(echo "$SEND" | jqv "['data']['devCode']")\"}")
PTOKEN=$(echo "$LOGIN" | jqv "['data']['accessToken']")

echo "== 2.价格下发(服务端计价: 900/9900/19900/29900分) =="
req "$BASE/api/member/skus" -H "Authorization: Bearer $PTOKEN"; echo

echo "== 3.下单月卡 =="
ORDER=$(req -X POST "$BASE/api/member/order" -H "Authorization: Bearer $PTOKEN" -d '{"sku":1,"channel":0}')
echo "$ORDER"
ONO=$(echo "$ORDER" | jqv "['data']['orderNo']")

echo "== 4.mock回调发货 =="
req -X POST "$BASE/api/member/pay/notify/mock" -H "Authorization: Bearer $PTOKEN" -d "{\"orderNo\":\"$ONO\"}"; echo
echo "5.重复回调(应幂等,不重复延期):"
req -X POST "$BASE/api/member/pay/notify/mock" -H "Authorization: Bearer $PTOKEN" -d "{\"orderNo\":\"$ONO\"}"; echo

echo "== 6.会员状态(tier=1, 约+30天) =="
req "$BASE/api/member/status" -H "Authorization: Bearer $PTOKEN"; echo

echo "== 7.下单SVIP永久 → 回调 → tier=2 永久 =="
O2=$(req -X POST "$BASE/api/member/order" -H "Authorization: Bearer $PTOKEN" -d '{"sku":4,"channel":0}')
O2NO=$(echo "$O2" | jqv "['data']['orderNo']")
req -X POST "$BASE/api/member/pay/notify/mock" -H "Authorization: Bearer $PTOKEN" -d "{\"orderNo\":\"$O2NO\"}" > /dev/null
req "$BASE/api/member/status" -H "Authorization: Bearer $PTOKEN"; echo

echo "== 8.广告换天数 ×2 (今天已发2/10) =="
req -X POST "$BASE/api/member/ad/callback" -H "Authorization: Bearer $PTOKEN" -d '{"callbackId":"cb-test-001"}'; echo
req -X POST "$BASE/api/member/ad/callback" -H "Authorization: Bearer $PTOKEN" -d '{"callbackId":"cb-test-002"}'; echo

echo "== 9.同callbackId重放(应 duplicate 不发奖) =="
req -X POST "$BASE/api/member/ad/callback" -H "Authorization: Bearer $PTOKEN" -d '{"callbackId":"cb-test-001"}'; echo

echo "== 10.补单演练: 下单→标记已支付未发货→等补单Job(60s)自动发货 =="
O3=$(req -X POST "$BASE/api/member/order" -H "Authorization: Bearer $PTOKEN" -d '{"sku":1,"channel":0}')
O3NO=$(echo "$O3" | jqv "['data']['orderNo']")
req -X POST "$BASE/api/member/pay/markpaid" -H "Authorization: Bearer $PTOKEN" -d "{\"orderNo\":\"$O3NO\"}"; echo
echo "等待补单Job(最长70秒)..."
for i in $(seq 1 14); do
  ST=$(req "$BASE/api/member/orders" -H "Authorization: Bearer $PTOKEN" | python -c "
import json,sys
d=json.load(sys.stdin)['data']['list']
o=[x for x in d if x['orderNo']=='$O3NO']
print(o[0]['status'] if o else '?')")
  if [ "$ST" = "2" ]; then echo "第${i}次轮询: 补单发货成功(status=2)"; break; fi
  sleep 5
done

echo "== 11.退款(mock): SVIP后买的月卡订单 $O3NO =="
req -X POST "$BASE/api/member/refund/mock" -H "Authorization: Bearer $PTOKEN" -d "{\"orderNo\":\"$O3NO\"}"; echo
echo "== 12.订单列表 =="
req "$BASE/api/member/orders" -H "Authorization: Bearer $PTOKEN" | python -c "
import json,sys
for o in json.load(sys.stdin)['data']['list']:
    print(o['orderNo'], 'sku', o['sku'], 'status', o['status'])"
echo "== W5 E2E 完成 =="
