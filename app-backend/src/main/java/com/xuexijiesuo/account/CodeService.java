package com.xuexijiesuo.account;

import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 验证码服务（Redis 存储，TTL 限时）。
 * W2 开发版：短信未接服务商（依赖清单 2.7），send 直接回显 devCode；接入短信后移除 devCode 返回。
 * 键约定：sms:{phone} 验证码 5min | bind:{code} 学生绑定码 10min | refresh:{token} 刷新令牌 30d
 */
@Service
public class CodeService {

    private static final SecureRandom RND = new SecureRandom();
    private final StringRedisTemplate redis;

    public CodeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 发送短信验证码（开发模式返回明文码） */
    public String sendSmsCode(String phone) {
        String code = String.format("%06d", RND.nextInt(1_000_000));
        redis.opsForValue().set("sms:" + phone, code, Duration.ofMinutes(5));
        return code;
    }

    public boolean verifySmsCode(String phone, String code) {
        String key = "sms:" + phone;
        String saved = redis.opsForValue().get(key);
        if (saved != null && saved.equals(code)) {
            redis.delete(key); // 一次性
            return true;
        }
        return false;
    }

    /** 生成学生设备绑定码（10 分钟有效，一次性，对应 FR-33） */
    public String createBindCode(long familyId) {
        String code = String.format("%06d", RND.nextInt(1_000_000));
        redis.opsForValue().set("bind:" + code, String.valueOf(familyId), Duration.ofMinutes(10));
        return code;
    }

    /** 消费绑定码，返回 familyId；无效返回 null */
    public Long consumeBindCode(String code) {
        String key = "bind:" + code;
        String v = redis.opsForValue().get(key);
        if (v == null) return null;
        redis.delete(key);
        return Long.parseLong(v);
    }

    /** 保存/读取 refresh 令牌（旋转：旧令牌用后即删） */
    public void saveRefresh(String token, long familyId) {
        redis.opsForValue().set("refresh:" + token, String.valueOf(familyId), Duration.ofDays(30));
    }

    public Long consumeRefresh(String token) {
        String key = "refresh:" + token;
        String v = redis.opsForValue().get(key);
        if (v == null) return null; // 不存在=已旋转或伪造 → 视为泄露信号
        redis.delete(key);
        return Long.parseLong(v);
    }
}
