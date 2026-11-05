package com.xuexijiesuo.member;

import com.xuexijiesuo.account.TokenService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 会员与订单（W5，FR-25/28 资金三件套）。
 * 渠道说明：微信/支付宝真实 SDK 待 D-06 签约，本期用 mock 回调跑通全链路；
 * 幂等发货 = Redis SETNX + DB 条件更新双保险；补单 Job 扫已支付未发货；资损告警写 alarm 表。
 * 金额红线：全部分存储，服务端计价，客户端传值无效（FR-28-3）。
 */
@RestController
@RequestMapping("/api/member")
public class MemberController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final StringRedisTemplate redis;

    private static final Map<Integer, String> SKU_NAMES = Map.of(1, "月卡", 2, "年卡", 3, "VIP永久", 4, "SVIP永久");
    private static final Map<Integer, Integer> SKU_DAYS = Map.of(1, 30, 2, 365, 3, 36500, 4, 36500);
    private static final Map<Integer, Integer> SKU_TIER = Map.of(1, 1, 2, 1, 3, 1, 4, 2);
    private static final String PERMANENT = "9999-12-31 23:59:59";

    public MemberController(JdbcClient db, JdbcTemplate jt, TokenService tokens, StringRedisTemplate redis) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
        this.redis = redis;
    }

    public record OrderReq(Integer sku, Integer channel) {}
    public record NotifyReq(String orderNo) {}
    public record MarkPaidReq(String orderNo) {}
    public record RefundReq(String orderNo) {}

    // ---------- 套餐与价格（服务端下发，客户端禁硬编码 FR-25） ----------
    @GetMapping("/skus")
    public Map<String, Object> skus() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int sku : List.of(1, 2, 3, 4)) {
            int price = priceOf(sku);
            list.add(Map.of("sku", sku, "name", SKU_NAMES.get(sku), "price", price,
                "priceYuan", String.format("%.2f", price / 100.0)));
        }
        return ok(Map.of("list", list));
    }

    private int priceOf(int sku) {
        String key = switch (sku) { case 1 -> "sku.month.price"; case 2 -> "sku.year.price";
            case 3 -> "sku.vip.price"; default -> "sku.svip.price"; };
        return Integer.parseInt(db.sql("SELECT config_value FROM sys_config WHERE config_key=?")
            .param(key).query(String.class).optional().orElseThrow(() -> bad("价格未配置:" + key)));
    }

    // ---------- 下单（家长 scope，服务端计价） ----------
    @PostMapping("/order")
    public Map<String, Object> order(@RequestHeader("Authorization") String auth, @RequestBody OrderReq req) {
        long familyId = parentClaims(auth);
        if (req.sku() == null || !SKU_NAMES.containsKey(req.sku())) throw bad("套餐非法");
        int amount = priceOf(req.sku());
        String orderNo = "XJ" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        int channel = req.channel() == null ? 1 : req.channel(); // 1微信 2支付宝 3IAP 0mock
        jt.update("INSERT INTO vip_order(order_no, family_id, sku, amount, channel, status) VALUES(?,?,?,?,?,0)",
            orderNo, familyId, req.sku(), amount, channel);
        return ok(Map.of("orderNo", orderNo, "amount", amount,
            "payUrl", "mock://pay/" + orderNo)); // TODO(D-06): 替换为渠道收银台
    }

    // ---------- 模拟支付成功（开发专用，D-06 后删除；真实回调走验签+查单双通道） ----------
    @PostMapping("/pay/notify/mock")
    public Map<String, Object> payNotifyMock(@RequestHeader("Authorization") String auth, @RequestBody NotifyReq req) {
        parentClaims(auth);
        return ship(req.orderNo(), "MOCK");
    }

    // 标记已支付未发货（模拟回调丢失场景，供补单 Job 演练）
    @PostMapping("/pay/markpaid")
    public Map<String, Object> markPaid(@RequestHeader("Authorization") String auth, @RequestBody MarkPaidReq req) {
        parentClaims(auth);
        int n = jt.update("UPDATE vip_order SET status=1 WHERE order_no=? AND status=0", req.orderNo());
        return ok(Map.of("marked", n > 0));
    }

    // ---------- 幂等发货（资金三件套核心，FR-28-2） ----------
    private Map<String, Object> ship(String orderNo, String channelTag) {
        Map<String, Object> order = db.sql("SELECT id, family_id, sku, amount, status FROM vip_order WHERE order_no=?")
            .param(orderNo).query((rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("familyId", rs.getLong("family_id"));
                m.put("sku", rs.getInt("sku"));
                m.put("amount", rs.getInt("amount"));
                m.put("status", rs.getInt("status"));
                return m;
            }).optional().orElseThrow(() -> bad("订单不存在"));
        int status = ((Number) order.get("status")).intValue();
        if (status >= 2 && status != 4) return ok(Map.of("shipped", true, "idempotent", true)); // 重复回调幂等
        if (status == 4 || status == 5) throw bad("订单已退款，禁止发货"); // 状态回跳防护(资损规则③)

        Boolean first = redis.opsForValue().setIfAbsent("ship:" + orderNo, "1", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(first)) { // Redis 层重复 → 资损告警(规则①)但仍查 DB 确认
            jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
                ((Number) order.get("familyId")).longValue(), "LOSS_DUP_SHIP", "重复发货告警",
                "订单 " + orderNo + " 收到重复发货请求（幂等拦截）");
        }
        String channelOrderNo = channelTag + "-" + orderNo + "-" + System.currentTimeMillis();
        int updated = jt.update("UPDATE vip_order SET status=2, channel_order_no=?, shipped_at=NOW() WHERE order_no=? AND status IN (0,1)",
            channelOrderNo, orderNo);
        if (updated == 0) return ok(Map.of("shipped", true, "idempotent", true)); // 并发保护
        deliver(((Number) order.get("familyId")).longValue(), ((Number) order.get("sku")).intValue());
        return ok(Map.of("shipped", true, "idempotent", false));
    }

    // ---------- 会员延期（tier 取高、expire 取长；永久为 9999-12-31） ----------
    private void deliver(long familyId, int sku) {
        int days = SKU_DAYS.get(sku);
        int newTier = SKU_TIER.get(sku);
        Map<String, Object> cur = db.sql("SELECT tier, expire_at FROM member WHERE family_id=?").param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("tier", rs.getInt("tier"));
                m.put("expire", rs.getString("expire_at"));
                return m;
            }).optional().orElse(null);
        int tier = newTier;
        String expire;
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        if (cur == null) {
            expire = days >= 36500 ? PERMANENT
                : LocalDateTime.now().plusDays(days).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } else {
            int curTier = ((Number) cur.get("tier")).intValue();
            String curExp = (String) cur.get("expire");
            tier = Math.max(curTier, newTier);
            if (days >= 36500 || (curExp != null && curExp.startsWith("9999"))) {
                expire = PERMANENT;
            } else {
                String base = curExp != null && curExp.compareTo(now) > 0 ? curExp : now;
                expire = LocalDateTime.parse(base.replace("T", " ").substring(0, 19))
                    .plusDays(days).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        }
        jt.update("""
            INSERT INTO member(family_id, tier, expire_at) VALUES(?,?,?)
            ON DUPLICATE KEY UPDATE tier=VALUES(tier), expire_at=VALUES(expire_at)
            """, familyId, tier, expire);
        jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
            familyId, "MEMBER_SHIPPED", "会员发货成功",
            "套餐：" + SKU_NAMES.get(sku) + "，会员到期：" + expire);
    }

    // ---------- 会员状态 ----------
    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader("Authorization") String auth) {
        long familyId = parentClaims(auth);
        Map<String, Object> data = new LinkedHashMap<>();
        db.sql("SELECT tier, expire_at FROM member WHERE family_id=?").param(familyId)
            .query((rs, i) -> {
                data.put("tier", rs.getInt("tier"));
                data.put("expireAt", rs.getString("expire_at"));
                return data;
            }).optional().orElseGet(() -> { data.put("tier", 0); data.put("expireAt", null); return data; });
        db.sql("SELECT trial_expire_at, ad_free_expire_at FROM free_time_account WHERE family_id=?").param(familyId)
            .query((rs, i) -> {
                data.put("trialExpireAt", rs.getString("trial_expire_at"));
                data.put("adFreeExpireAt", rs.getString("ad_free_expire_at"));
                return data;
            }).optional().ifPresent(x -> {});
        return ok(data);
    }

    // ---------- 订单列表 ----------
    @GetMapping("/orders")
    public Map<String, Object> orders(@RequestHeader("Authorization") String auth) {
        long familyId = parentClaims(auth);
        List<Map<String, Object>> list = db.sql("""
                SELECT order_no, sku, amount, channel, status, created_at, shipped_at FROM vip_order
                WHERE family_id=? ORDER BY id DESC LIMIT 50
                """).param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orderNo", rs.getString("order_no"));
                m.put("sku", rs.getInt("sku"));
                m.put("amount", rs.getInt("amount"));
                m.put("status", rs.getInt("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    // ---------- 退款（W5 简化：全退折算终止；7天无理由细则待法务确认后细化 FR-28-7） ----------
    @PostMapping("/refund/mock")
    public Map<String, Object> refundMock(@RequestHeader("Authorization") String auth, @RequestBody RefundReq req) {
        long familyId = parentClaims(auth);
        int n = jt.update("UPDATE vip_order SET status=5, refund_at=NOW() WHERE order_no=? AND family_id=? AND status=2",
            req.orderNo(), familyId);
        if (n == 0) throw bad("仅已发货订单可退款");
        jt.update("UPDATE member SET tier=0, expire_at=NOW() WHERE family_id=?", familyId); // TODO: 按剩余天数折算
        jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
            familyId, "MEMBER_REFUND", "退款成功", "订单 " + req.orderNo() + " 已退款，会员权益终止");
        return ok(Map.of("refunded", true));
    }

    // ---------- 补单 Job（FR-28-6：已支付未发货 >1 分钟主动发货；生产换 XXL-Job） ----------
    @Scheduled(fixedDelay = 60_000)
    public void replenishJob() {
        List<String> pendings = db.sql("SELECT order_no FROM vip_order WHERE status=1")
            .query((rs, i) -> rs.getString("order_no")).list();
        for (String orderNo : pendings) {
            try {
                ship(orderNo, "REPLENISH");
            } catch (Exception e) {
                jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(0,'LOSS_REPLENISH_FAIL','补单失败','订单 "
                    + orderNo + " 补单异常：" + e.getMessage() + "')");
            }
        }
        if (!pendings.isEmpty()) {
            // TODO(D-06): T+1 渠道对账在此接入（技术架构 §3.3），差异写 reconcile_diff
        }
    }

    private long parentClaims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可操作");
        return c.familyId();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
