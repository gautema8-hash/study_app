package com.xuexijiesuo.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 认证接口（W2 开发版，对应 FR-31~35）。
 * 已实现：短信登录(dev验证码)/refresh旋转/身份选择/绑定码/学生设备绑定/家长密码。
 * 待接入：微信 OAuth（依赖 D-08 资质）、JWT 过滤器全量鉴权（当前 SecurityConfig 放行）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final CodeService codes;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthController(JdbcClient db, JdbcTemplate jt, TokenService tokens, CodeService codes) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
        this.codes = codes;
    }

    public record SmsSendReq(String phone) {}
    public record SmsLoginReq(String phone, String code) {}
    public record RefreshReq(String refreshToken) {}
    public record IdentityReq(String scope, Long profileId) {}
    public record BindCodeReq(String nickname, Integer grade) {}
    public record StudentBindReq(String bindCode, String deviceFingerprint, String deviceName, Integer osType) {}
    public record ParentPwdReq(String password, String oldPassword) {}

    // 1. 发送短信验证码（开发模式回显 devCode）
    @PostMapping("/sms/send")
    public Map<String, Object> sendSms(@RequestBody SmsSendReq req) {
        if (req.phone() == null || !req.phone().matches("^1\\d{10}$"))
            throw bad("手机号格式错误");
        return ok(Map.of("devCode", codes.sendSmsCode(req.phone()))); // TODO: 接入短信服务商后移除回显
    }

    // 2. 短信登录：验证码 → 创建/命中家庭账号 → 签发双令牌（FR-31/34）
    @PostMapping("/sms/login")
    public Map<String, Object> smsLogin(@RequestBody SmsLoginReq req) {
        if (!codes.verifySmsCode(req.phone(), req.code())) throw bad("验证码错误或已过期");
        long familyId = upsertFamilyByPhone(req.phone());
        return ok(issueTokens(familyId, "parent"));
    }

    // 3. refresh 旋转（旧令牌用后即删；复用=泄露信号，TODO 接风控全账号撤销）
    @PostMapping("/token/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshReq req) {
        Long familyId = codes.consumeRefresh(req.refreshToken());
        if (familyId == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh 无效或已旋转");
        return ok(issueTokens(familyId, "parent"));
    }

    // 4. 身份选择：签发对应 scope 的 access（家长/学生，FR-32）
    @PostMapping("/identity/select")
    public Map<String, Object> selectIdentity(@RequestHeader("Authorization") String auth, @RequestBody IdentityReq req) {
        long familyId = requireFamily(auth);
        long ver = credVersion(familyId);
        if ("parent".equals(req.scope())) {
            // TODO(W安全迭代): 家长 scope 要求家长密码二次验证(T-08)与实名核验状态检查
            return ok(Map.of("accessToken", tokens.createAccess(familyId, "parent", null, ver), "scope", "parent"));
        }
        if ("student".equals(req.scope())) {
            if (req.profileId() == null || !belongsTo(req.profileId(), familyId))
                throw bad("学生档案不存在或不属于该家庭");
            return ok(Map.of("accessToken", tokens.createAccess(familyId, "student", req.profileId(), ver),
                "scope", "student", "profileId", req.profileId()));
        }
        throw bad("scope 仅支持 parent/student");
    }

    // 5. 家长生成学生设备绑定码（10 分钟一次性，FR-33）
    @PostMapping("/student/bindcode")
    public Map<String, Object> createBindCode(@RequestHeader("Authorization") String auth,
                                              @RequestBody(required = false) BindCodeReq req) {
        long familyId = requireFamily(auth);
        Long profileId;
        if (req != null && req.nickname() != null) {
            Integer grade = req.grade() == null ? 3 : req.grade();
            profileId = insertAndGetId(
                "INSERT INTO student_profile(family_id, nickname, grade) VALUES(?,?,?)",
                familyId, req.nickname(), grade);
        } else {
            profileId = db.sql("SELECT id FROM student_profile WHERE family_id=? AND is_delete=0 ORDER BY id LIMIT 1")
                .param(familyId).query(Long.class).optional().orElse(null);
            if (profileId == null) throw bad("请先提供孩子昵称与年级以创建学生档案");
        }
        return ok(Map.of("bindCode", codes.createBindCode(familyId), "profileId", profileId));
    }

    // 6. 学生设备绑定：消费绑定码 → 绑定设备+档案 → 签发学生 token（E-05/FR-33/34）
    @PostMapping("/student/bind")
    public Map<String, Object> studentBind(@RequestBody StudentBindReq req) {
        Long familyId = codes.consumeBindCode(req.bindCode());
        if (familyId == null) throw bad("绑定码无效或已过期");
        Long profileId = db.sql("SELECT id FROM student_profile WHERE family_id=? AND is_delete=0 ORDER BY id LIMIT 1")
            .param(familyId).query(Long.class).optional().orElseThrow(() -> bad("家庭无学生档案"));
        db.sql("UPDATE device_bind SET bind_status=0 WHERE profile_id=? AND device_mode=1 AND is_delete=0")
            .param(profileId).update(); // 旧学生模式设备下线（互踢，FR-34）
        byte[] fp = req.deviceFingerprint().getBytes(StandardCharsets.UTF_8);
        Long exist = db.sql("SELECT id FROM device_bind WHERE device_fingerprint=? AND device_mode=1")
            .param(fp).query(Long.class).optional().orElse(null);
        Long bindId;
        if (exist != null) {
            // 该指纹曾被逻辑删除 → 复用原行并复活（避免 uk_fingerprint_mode 唯一键冲突）
            jt.update("UPDATE device_bind SET bind_status=1, profile_id=?, device_name=?, is_delete=0 WHERE id=?",
                profileId, req.deviceName(), exist);
            bindId = exist;
        } else {
            bindId = insertAndGetId(
                "INSERT INTO device_bind(family_id, profile_id, device_fingerprint, device_name, device_mode, os_type) VALUES(?,?,?,?,1,?)",
                familyId, profileId, fp, req.deviceName(), req.osType());
        }
        return ok(Map.of("deviceBindId", bindId, "profileId", profileId,
            "accessToken", tokens.createAccess(familyId, "student", profileId, credVersion(familyId))));
    }

    // 7. 家长操作密码（6位，独立于登录凭证，FR-35；孩子设备不存密码）
    // 密码状态：供客户端决定展示「设置」还是「修改」，避免要求输入密码却没有设置入口
    @GetMapping("/password/parent/status")
    public Map<String, Object> parentPwdStatus(@RequestHeader("Authorization") String auth) {
        long familyId = requireFamily(auth);
        String hash = db.sql("SELECT parent_pwd_hash FROM family_account WHERE id=? AND is_delete=0").param(familyId)
            .query(String.class).optional().orElse(null);
        return ok(Map.of("hasPassword", hash != null && !hash.isBlank()));
    }

    @PostMapping("/password/parent")
    public Map<String, Object> setParentPwd(@RequestHeader("Authorization") String auth, @RequestBody ParentPwdReq req) {
        // 设置/修改仅允许家长身份，防止孩子端直接改密码绕过身份切换校验
        TokenService.TokenClaims c = claims(auth);
        if (!"parent".equals(c.scope()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可设置或修改操作密码");
        long familyId = c.familyId();
        if (req.password() == null || !req.password().matches("^\\d{6}$")) throw bad("操作密码须为6位数字");
        String hash = db.sql("SELECT parent_pwd_hash FROM family_account WHERE id=? AND is_delete=0").param(familyId)
            .query(String.class).optional().orElse(null);
        boolean has = hash != null && !hash.isBlank();
        if (has && (req.oldPassword() == null || !encoder.matches(req.oldPassword(), hash)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "原操作密码错误，无法修改");
        jt.update("UPDATE family_account SET parent_pwd_hash=? WHERE id=? AND is_delete=0", encoder.encode(req.password()), familyId);
        return ok(Map.of("set", true, "mode", has ? "modified" : "created"));
    }

    @PostMapping("/password/parent/verify")
    public Map<String, Object> verifyParentPwd(@RequestHeader("Authorization") String auth, @RequestBody ParentPwdReq req) {
        long familyId = requireFamily(auth);
        String hash = db.sql("SELECT parent_pwd_hash FROM family_account WHERE id=? AND is_delete=0").param(familyId)
            .query(String.class).optional().orElse(null);
        boolean pass = hash != null && encoder.matches(req.password(), hash);
        if (!pass) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "操作密码错误"); // TODO: 错5次锁30分钟+告警(T-08)
        return ok(Map.of("verified", true));
    }

    // 8. 当前令牌信息（联调用）
    @GetMapping("/me")
    public Map<String, Object> me(@RequestHeader("Authorization") String auth) {
        var c = claims(auth);
        Map<String, Object> data = new java.util.LinkedHashMap<>(); // HashMap/Map.of 不允许 null 值
        data.put("familyId", c.familyId());
        data.put("scope", c.scope());
        data.put("profileId", c.profileId());
        data.put("ver", c.ver());
        return ok(data);
    }

    // ---------- helpers ----------

    private long upsertFamilyByPhone(String phone) {
        String hash = sha256(phone);
        var existing = db.sql("SELECT id FROM family_account WHERE phone_hash=? AND is_delete=0").param(hash)
            .query(Long.class).optional();
        if (existing.isPresent()) return existing.get();
        // 软删除的家庭重新登录时复用原记录并恢复（避免 phone_hash 唯一键冲突）
        return insertAndGetId("INSERT INTO family_account(phone_enc, phone_hash) VALUES(?,?) "
            + "ON DUPLICATE KEY UPDATE is_delete=0, phone_enc=VALUES(phone_enc)",
            phone.getBytes(StandardCharsets.UTF_8), hash); // TODO(W安全迭代): 应用层AES加密替代明文字节
    }

    private Map<String, Object> issueTokens(long familyId, String scope) {
        long ver = credVersion(familyId);
        String access = tokens.createAccess(familyId, scope, null, ver);
        String refresh = java.util.UUID.randomUUID().toString().replace("-", "");
        codes.saveRefresh(refresh, familyId);
        List<Long> profiles = db.sql("SELECT id FROM student_profile WHERE family_id=? AND is_delete=0")
            .param(familyId).query(Long.class).list();
        return Map.of("accessToken", access, "refreshToken", refresh, "familyId", familyId, "profileIds", profiles);
    }

    private long requireFamily(String auth) {
        return claims(auth).familyId();
    }

    private TokenService.TokenClaims claims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 Bearer token");
        try {
            return tokens.verify(auth.substring(7));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    private boolean belongsTo(long profileId, long familyId) {
        return db.sql("SELECT COUNT(*) FROM student_profile WHERE id=? AND family_id=? AND is_delete=0")
            .param(profileId).param(familyId).query(Long.class).single() > 0;
    }

    private long credVersion(long familyId) {
        return db.sql("SELECT cred_version FROM family_account WHERE id=? AND is_delete=0").param(familyId)
            .query(Long.class).optional().orElse(1L);
    }

    private long insertAndGetId(String sql, Object... params) {
        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
