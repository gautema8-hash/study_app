package com.xuexijiesuo.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 轻量 HS256 JWT（W2 开发版）。
 * 设计依据：《认证与权限设计文档》§3 —— access 2h 自包含，refresh 用 Redis 不透明令牌（可撤销）。
 * TODO(W安全迭代): 生产换 RS256 + 密钥走 KMS；当前 HMAC 密钥来自环境变量 JWT_SECRET。
 */
@Service
public class TokenService {

    private final byte[] secret;

    public TokenService(@Value("${app.jwt.secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record TokenClaims(long familyId, String scope, Long profileId, long ver, long exp) {}

    public String createAccess(long familyId, String scope, Long profileId, long credVersion) {
        long now = System.currentTimeMillis() / 1000;
        long exp = now + 2 * 3600; // 2h
        String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = b64("{\"sub\":\"family:%d\",\"scope\":\"%s\",\"profile\":%s,\"ver\":%d,\"iat\":%d,\"exp\":%d}"
            .formatted(familyId, scope, profileId == null ? "null" : profileId, credVersion, now, exp));
        return header + "." + payload + "." + sign(header + "." + payload);
    }

    /** 校验签名与有效期，通过返回 claims，失败抛 IllegalStateException */
    public TokenClaims verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) throw new IllegalStateException("格式错误");
            if (!MessageDigest.isEqual(sign(parts[0] + "." + parts[1]).getBytes(), parts[2].getBytes()))
                throw new IllegalStateException("签名无效");
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            long exp = Long.parseLong(field(json, "exp"));
            if (exp < System.currentTimeMillis() / 1000) throw new IllegalStateException("已过期");
            long familyId = Long.parseLong(field(json, "sub").replace("family:", ""));
            String scope = field(json, "scope");
            Long profileId = "null".equals(field(json, "profile")) ? null
                : Long.parseLong(field(json, "profile"));
            long ver = Long.parseLong(field(json, "ver"));
            return new TokenClaims(familyId, scope, profileId, ver, exp);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("token 无效");
        }
    }

    /** 按 JSON 字段名提取值（字符串去引号；支持 null 字面量与数字；不受字段顺序影响） */
    private static String field(String json, String name) {
        var m = java.util.regex.Pattern.compile("\"" + name + "\":(\"[^\"]*\"|null|[-0-9.]+)").matcher(json);
        if (!m.find()) throw new IllegalStateException("字段缺失: " + name);
        String v = m.group(1);
        return "null".equals(v) ? v : (v.startsWith("\"") ? v.substring(1, v.length() - 1) : v);
    }

    private String sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String extract(String json, String key, String end) {
        int i = json.indexOf(key);
        if (i < 0) throw new IllegalStateException("字段缺失");
        i += key.length();
        int j = json.indexOf(end, i);
        return j < 0 ? json.substring(i) : json.substring(i, j);
    }
}
