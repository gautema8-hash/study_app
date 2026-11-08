package com.xuexijiesuo.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 后台接口统一异常响应：所有失败都返回 {code, msg, data}，msg 为中文业务提示。
 * 解决此前 Spring 默认英文错误体（Unauthorized / Bad Request / Internal Server Error）
 * 直接暴露给运营人员、且前端无法取到具体原因的问题。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(ResponseStatusException e) {
        return build(e.getStatusCode().value(), e.getReason());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleParamType(MethodArgumentTypeMismatchException e) {
        return build(400, "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleBody(HttpMessageNotReadableException e) {
        return build(400, "请求数据格式错误，请检查输入内容");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegal(IllegalArgumentException e) {
        return build(400, e.getMessage() == null ? "参数不合法" : e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        // 用户看到友好中文，服务端日志保留完整堆栈便于排查
        org.slf4j.LoggerFactory.getLogger(ApiExceptionHandler.class)
            .error("后台接口未处理异常", e);
        return build(500, "服务暂时不可用，请稍后重试或联系管理员");
    }

    private ResponseEntity<Map<String, Object>> build(int status, String msg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("msg", msg == null || msg.isBlank() ? "操作失败，请稍后重试" : msg);
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }
}
