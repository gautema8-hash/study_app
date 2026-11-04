package com.xuexijiesuo.common;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性探针：验证 App/后台 → 网关 → 服务 → MySQL/Redis 链路。
 * 统一响应格式：{code, msg, data}（错误码分段见技术架构文档 §4）。
 */
@RestController
@RequestMapping("/api/common")
public class PingController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", "xuexijiesuo-backend");
        data.put("time", LocalDateTime.now().toString());
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
