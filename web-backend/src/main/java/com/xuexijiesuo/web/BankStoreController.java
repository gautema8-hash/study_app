package com.xuexijiesuo.web;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 题库商店运营端（PRD《题库商店功能需求文档》FR-S01~S07）。
 * 状态机：0草稿 →1待审核 →2已上架 →3已下架(可重编辑提交)；1→4驳回(可重编辑提交)。
 * 写权限：REVIEWER/ADMIN（AdminAuthInterceptor 按路径域放行）。
 */
@RestController
@RequestMapping("/api/ops/store")
public class BankStoreController {

    private final JdbcClient db;
    private final JdbcTemplate jt;

    public BankStoreController(JdbcClient db, JdbcTemplate jt) {
        this.db = db;
        this.jt = jt;
    }

    public record ItemUpsert(String title, String description, Integer subject, Integer grade,
                             List<Long> questionIds) {}

    public record ReviewReq(boolean pass, String reason) {}

    public record TagsReq(List<Long> tagIds) {}

    public record TagCreate(String name, String color) {}

    // ---------- FR-S07 列表 ----------
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) Integer status,
                                     @RequestParam(required = false) Integer subject,
                                     @RequestParam(required = false) Integer grade,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(defaultValue = "1") Integer page,
                                     @RequestParam(defaultValue = "20") Integer size) {
        size = Math.min(size, 100);
        page = Math.max(page, 1);
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (status != null) { where.append(" AND status=?"); params.add(status); }
        if (subject != null) { where.append(" AND subject=?"); params.add(subject); }
        if (grade != null) { where.append(" AND grade=?"); params.add(grade); }
        if (keyword != null && !keyword.isBlank()) { where.append(" AND title LIKE ?"); params.add("%" + keyword.trim() + "%"); }
        Long total = db.sql("SELECT COUNT(*) FROM bank_store_item" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT i.id, i.title, i.description, i.subject, i.grade, i.question_count,
                       i.download_count, i.status, i.review_reason, i.reviewed_by, i.published_at, i.created_at,
                       (SELECT GROUP_CONCAT(t.name) FROM bank_store_item_tag it
                         JOIN bank_store_tag t ON t.id=it.tag_id
                        WHERE it.store_item_id=i.id) AS tags
                FROM bank_store_item i""" + where + " ORDER BY i.id DESC LIMIT ? OFFSET ?")
            .params(params).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("title", rs.getString("title"));
                m.put("description", rs.getString("description"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("questionCount", rs.getInt("question_count"));
                m.put("downloadCount", rs.getInt("download_count"));
                m.put("status", rs.getInt("status"));
                m.put("tags", rs.getString("tags") == null ? List.of() : List.of(rs.getString("tags").split(",")));
                m.put("reviewReason", rs.getString("review_reason"));
                m.put("publishedAt", rs.getString("published_at"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("total", total, "page", page, "list", list));
    }

    // ---------- 详情（含题目清单与标签） ----------
    @GetMapping("/items/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> item = db.sql("SELECT id, title, description, subject, grade, question_count, download_count, status, review_reason, created_at FROM bank_store_item WHERE id=?")
            .param(id).query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("title", rs.getString("title"));
                m.put("description", rs.getString("description"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("questionCount", rs.getInt("question_count"));
                m.put("downloadCount", rs.getInt("download_count"));
                m.put("status", rs.getInt("status"));
                m.put("reviewReason", rs.getString("review_reason"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).optional().orElseThrow(() -> bad("题库不存在"));
        item.put("questionIds", db.sql("SELECT question_id FROM bank_store_question WHERE store_item_id=? ORDER BY sort_no, id")
            .param(id).query((rs, i) -> rs.getLong("question_id")).list());
        item.put("tagIds", db.sql("SELECT tag_id FROM bank_store_item_tag WHERE store_item_id=?")
            .param(id).query((rs, i) -> rs.getLong("tag_id")).list());
        return ok(item);
    }

    // ---------- FR-S01 创建草稿 ----------
    @PostMapping("/items")
    @Transactional
    public Map<String, Object> create(@RequestBody ItemUpsert req) {
        List<Long> qids = validate(req);
        long adminId = currentAdminId();
        String code = "BS" + System.currentTimeMillis();
        jt.update("INSERT INTO bank_store_item(code, title, description, subject, grade, question_count, status, created_by) VALUES(?,?,?,?,?,?,0,?)",
            code, req.title().trim(), req.description(), req.subject(), req.grade(), qids.size(), adminId);
        Long itemId = db.sql("SELECT id FROM bank_store_item WHERE code=?").param(code).query(Long.class).single();
        saveQuestions(itemId, qids);
        return ok(Map.of("id", itemId));
    }

    // ---------- FR-S02 编辑（仅 0草稿/3已下架/4驳回） ----------
    @PutMapping("/items/{id}")
    @Transactional
    public Map<String, Object> update(@PathVariable Long id, @RequestBody ItemUpsert req) {
        List<Long> qids = validate(req);
        int n = jt.update("UPDATE bank_store_item SET title=?, description=?, subject=?, grade=? WHERE id=? AND status IN (0,3,4)",
            req.title().trim(), req.description(), req.subject(), req.grade(), id);
        if (n == 0) throw bad("题库不存在或当前状态不可编辑");
        jt.update("DELETE FROM bank_store_question WHERE store_item_id=?", id);
        saveQuestions(id, qids);
        return ok(Map.of("updated", true));
    }

    // ---------- FR-S03 提交审核（0/3/4 → 1） ----------
    @PostMapping("/items/{id}/submit")
    public Map<String, Object> submit(@PathVariable Long id) {
        int n = jt.update("UPDATE bank_store_item SET status=1, review_reason=NULL WHERE id=? AND status IN (0,3,4)", id);
        if (n == 0) throw bad("仅草稿/下架/驳回状态可提交审核");
        return ok(Map.of("submitted", true));
    }

    // ---------- FR-S04 审核裁决（1 → 2上架 / 4驳回，REVIEWER/ADMIN） ----------
    @PostMapping("/items/{id}/review")
    public Map<String, Object> review(@PathVariable Long id, @RequestBody ReviewReq req) {
        long adminId = currentAdminId();
        if (req.pass()) {
            List<Long> qids = db.sql("SELECT question_id FROM bank_store_question WHERE store_item_id=?")
                .param(id).query((rs, i) -> rs.getLong("question_id")).list();
            if (qids.isEmpty()) throw bad("题包内无有效题目，不可上架");
            Integer badCount = db.sql("SELECT COUNT(*) FROM question q JOIN bank_store_question b ON b.question_id=q.id " +
                    "WHERE b.store_item_id=? AND (q.family_id IS NOT NULL OR q.status<>1 OR q.review_status<>0)")
                .param(id).query(Integer.class).single();
            if (badCount > 0) throw bad("题包含 %d 道非内置/未上架/机审未过题目，不可上架".formatted(badCount));
            int n = jt.update("UPDATE bank_store_item SET status=2, reviewed_by=?, reviewed_at=NOW(), review_reason=NULL, published_at=NOW() WHERE id=? AND status=1", adminId, id);
            if (n == 0) throw bad("仅待审核状态可裁决");
        } else {
            if (req.reason() == null || req.reason().isBlank()) throw bad("驳回必须填写原因");
            int n = jt.update("UPDATE bank_store_item SET status=4, reviewed_by=?, reviewed_at=NOW(), review_reason=? WHERE id=? AND status=1", adminId, req.reason().trim(), id);
            if (n == 0) throw bad("仅待审核状态可裁决");
        }
        return ok(Map.of("decided", true));
    }

    // ---------- FR-S05 下架（2 → 3） ----------
    @PostMapping("/items/{id}/offline")
    public Map<String, Object> offline(@PathVariable Long id) {
        int n = jt.update("UPDATE bank_store_item SET status=3 WHERE id=? AND status=2", id);
        if (n == 0) throw bad("仅已上架题库可下架");
        return ok(Map.of("offline", true));
    }

    // ---------- FR-S06 打标签（覆盖式，≤5） ----------
    @PutMapping("/items/{id}/tags")
    @Transactional
    public Map<String, Object> setTags(@PathVariable Long id, @RequestBody TagsReq req) {
        List<Long> tagIds = req.tagIds() == null ? List.of() : req.tagIds().stream().distinct().toList();
        if (tagIds.size() > 5) throw bad("标签最多5个");
        int n = db.sql("SELECT COUNT(*) FROM bank_store_item WHERE id=?").param(id).query(Integer.class).single();
        if (n == 0) throw bad("题库不存在");
        if (!tagIds.isEmpty()) {
            Long hit = db.sql("SELECT COUNT(*) FROM bank_store_tag WHERE id IN (" + "?,".repeat(tagIds.size() - 1) + "?)")
                .params(tagIds).query(Long.class).single();
            if (hit != tagIds.size()) throw bad("存在无效标签");
        }
        jt.update("DELETE FROM bank_store_item_tag WHERE store_item_id=?", id);
        for (Long tagId : tagIds) jt.update("INSERT INTO bank_store_item_tag(store_item_id, tag_id) VALUES(?,?)", id, tagId);
        return ok(Map.of("tagIds", tagIds));
    }

    // ---------- FR-S06 标签 CRUD ----------
    @GetMapping("/tags")
    public Map<String, Object> tags() {
        List<Map<String, Object>> list = db.sql("SELECT id, name, color, created_at FROM bank_store_tag ORDER BY id")
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("name", rs.getString("name"));
                m.put("color", rs.getString("color"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    @PostMapping("/tags")
    public Map<String, Object> createTag(@RequestBody TagCreate req) {
        if (req.name() == null || req.name().isBlank() || req.name().trim().length() > 16) throw bad("标签名必填且≤16字");
        try {
            jt.update("INSERT INTO bank_store_tag(name, color) VALUES(?,?)", req.name().trim(),
                req.color() == null || req.color().isBlank() ? "blue" : req.color().trim());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw bad("标签已存在");
        }
        Long id = db.sql("SELECT id FROM bank_store_tag WHERE name=?").param(req.name().trim()).query(Long.class).single();
        return ok(Map.of("id", id));
    }

    // ---------- helpers ----------
    private List<Long> validate(ItemUpsert req) {
        List<String> errs = new ArrayList<>();
        if (req.title() == null || req.title().isBlank() || req.title().trim().length() > 64) errs.add("名称必填且≤64字");
        if (req.description() != null && req.description().length() > 200) errs.add("简介≤200字");
        if (req.subject() == null || req.subject() < 1 || req.subject() > 10) errs.add("科目非法(1-10)");
        if (req.grade() == null || req.grade() < 1 || req.grade() > 12) errs.add("年级非法(1-12)");
        if (!errs.isEmpty()) throw bad(String.join("; ", errs));
        List<Long> qids = req.questionIds() == null ? List.of() : req.questionIds().stream().distinct().toList();
        if (qids.isEmpty() || qids.size() > 200) throw bad("题目数须为1-200");
        Integer okCount = db.sql("SELECT COUNT(*) FROM question WHERE id IN (" + "?,".repeat(qids.size() - 1) + "?) " +
                "AND family_id IS NULL AND status=1 AND review_status=0")
            .params(qids).query(Integer.class).single();
        if (okCount != qids.size()) throw bad("存在不可用题目（须为内置、上架且机审通过的题目）");
        return qids;
    }

    private void saveQuestions(long itemId, List<Long> qids) {
        for (int i = 0; i < qids.size(); i++)
            jt.update("INSERT IGNORE INTO bank_store_question(store_item_id, question_id, sort_no) VALUES(?,?,?)", itemId, qids.get(i), i);
    }

    /** 管理员身份由 AdminAuthInterceptor 注入（写操作强制），读接口仅审计用途 */
    private long currentAdminId() {
        return 0; // TODO(W安全迭代): JWT sub 携带 adminId 后回填审计字段
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
