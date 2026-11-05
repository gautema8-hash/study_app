package com.xuexijiesuo.bank;

import com.xuexijiesuo.account.TokenService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 题库商店·家长端（PRD《题库商店功能需求文档》FR-S08~S12，parent scope）。
 * 添加=题目复制入家庭题库（幂等，唯一键 family+item）；分配强校验子女归属当前家庭。
 */
@RestController
@RequestMapping("/api/bank/store")
public class BankStoreController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;

    public BankStoreController(JdbcClient db, JdbcTemplate jt, TokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record AssignReq(List<Long> profileIds) {}

    private record Ctx(long familyId) {}

    private Ctx parentCtx(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可访问"); // FR-10
        return new Ctx(c.familyId());
    }

    // ---------- FR-S08 商店列表（仅已上架；关键词+科目/年级/标签筛选+分页） ----------
    @GetMapping("/items")
    public Map<String, Object> list(@RequestHeader("Authorization") String auth,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Integer subject,
                                    @RequestParam(required = false) Integer grade,
                                    @RequestParam(required = false) Long tagId,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        long familyId = parentCtx(auth).familyId();
        size = Math.min(size, 100);
        page = Math.max(page, 1);
        StringBuilder where = new StringBuilder(" WHERE i.is_delete=0 AND i.status=2");
        List<Object> params = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) { where.append(" AND (i.title LIKE ? OR i.description LIKE ?)"); params.add("%" + keyword.trim() + "%"); params.add("%" + keyword.trim() + "%"); }
        if (subject != null) { where.append(" AND i.subject=?"); params.add(subject); }
        if (grade != null) { where.append(" AND i.grade=?"); params.add(grade); }
        if (tagId != null) { where.append(" AND EXISTS (SELECT 1 FROM bank_store_item_tag t WHERE t.store_item_id=i.id AND t.tag_id=? AND t.is_delete=0)"); params.add(tagId); }
        Long total = db.sql("SELECT COUNT(*) FROM bank_store_item i" + where).params(params).query(Long.class).single();
        List<Long> ids = db.sql("SELECT i.id FROM bank_store_item i" + where + " ORDER BY i.download_count DESC, i.id DESC LIMIT ? OFFSET ?")
            .params(params).param(size).param((page - 1) * size)
            .query((rs, i) -> rs.getLong("id")).list();
        if (ids.isEmpty()) return ok(Map.of("total", total, "page", page, "list", List.of()));
        List<Map<String, Object>> list = db.sql("""
                SELECT i.id, i.title, i.description, i.subject, i.grade, i.question_count, i.download_count,
                       (f.family_id IS NOT NULL) AS added,
                       (SELECT GROUP_CONCAT(CONCAT(t.name,':',t.color)) FROM bank_store_item_tag it
                         JOIN bank_store_tag t ON t.id=it.tag_id WHERE it.store_item_id=i.id AND it.is_delete=0 AND t.is_delete=0) AS tags
                FROM bank_store_item i
                LEFT JOIN family_bank_store f ON f.store_item_id=i.id AND f.family_id=%d AND f.is_delete=0
                WHERE i.id IN (%s) AND i.is_delete=0
                ORDER BY i.download_count DESC, i.id DESC
                """.formatted(familyId, ids.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0")))
            .query((rs, i) -> itemRow(rs, true)).list();
        return ok(Map.of("total", total, "page", page, "list", list));
    }

    // ---------- FR-S09 题库详情（含全部题目预览） ----------
    @GetMapping("/items/{id}")
    public Map<String, Object> detail(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        long familyId = parentCtx(auth).familyId();
        var rows = db.sql("""
                SELECT i.id, i.title, i.description, i.subject, i.grade, i.question_count, i.download_count,
                       (f.family_id IS NOT NULL) AS added,
                       (SELECT GROUP_CONCAT(CONCAT(t.name,':',t.color)) FROM bank_store_item_tag it
                         JOIN bank_store_tag t ON t.id=it.tag_id WHERE it.store_item_id=i.id AND it.is_delete=0 AND t.is_delete=0) AS tags
                FROM bank_store_item i
                LEFT JOIN family_bank_store f ON f.store_item_id=i.id AND f.family_id=? AND f.is_delete=0
                WHERE i.id=? AND i.status=2 AND i.is_delete=0
                """).params(List.of(familyId, id))
            .query((rs, i) -> itemRow(rs, true)).list();
        if (rows.isEmpty()) throw bad("题库不存在或已下架");
        Map<String, Object> item = rows.get(0);
        item.put("questions", db.sql("""
                SELECT q.stem, q.options_json, q.answer, q.analysis, q.q_type
                FROM bank_store_question b JOIN question q ON q.id=b.question_id
                WHERE b.store_item_id=? AND b.is_delete=0 AND q.is_delete=0 ORDER BY b.sort_no, b.id
                """).param(id)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("qType", rs.getInt("q_type"));
                m.put("stem", rs.getString("stem"));
                m.put("options", rs.getString("options_json"));
                m.put("answer", rs.getString("answer"));
                m.put("analysis", rs.getString("analysis"));
                return m;
            }).list());
        return ok(item);
    }

    // ---------- FR-S10 添加题库（幂等：复制题目入家庭题库，首次累加下载量） ----------
    @PostMapping("/items/{id}/add")
    @Transactional
    public Map<String, Object> add(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        long familyId = parentCtx(auth).familyId();
        var item = db.sql("SELECT id, subject, grade FROM bank_store_item WHERE id=? AND status=2 AND is_delete=0").param(id)
            .query((rs, i) -> Map.of("id", rs.getLong("id"))).optional().orElse(null);
        if (item == null) throw bad("题库不存在或已下架");
        // 幂等：已添加直接返回（客户端可安全重试）
        Long existing = db.sql("SELECT id FROM family_bank_store WHERE family_id=? AND store_item_id=? AND is_delete=0")
            .params(List.of(familyId, id)).query(Long.class).optional().orElse(null);
        if (existing != null) return ok(Map.of("added", false, "familyBankId", existing));
        // 复制题目 → 家庭题库（source=2，出题链路零改动）；逐题插入以精确记录新题 ID 映射
        Long bankId = insertFamilyBank(familyId, id);
        List<Long> srcIds = db.sql("SELECT question_id FROM bank_store_question WHERE store_item_id=? AND is_delete=0 ORDER BY sort_no, id")
            .param(id).query((rs, i) -> rs.getLong("question_id")).list();
        int copied = 0;
        for (Long srcId : srcIds) {
            KeyHolder kh = new GeneratedKeyHolder();
            jt.update(con -> {
                PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO question(family_id, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, source, status, review_status) " +
                    "SELECT ?, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, 2, 1, 0 FROM question q WHERE q.id=? AND q.is_delete=0",
                    Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, familyId);
                ps.setLong(2, srcId);
                return ps;
            }, kh);
            Number key = kh.getKey();
            Long newId = key == null ? null : key.longValue();
            if (newId != null) {
                jt.update("INSERT IGNORE INTO family_bank_store_question(family_bank_id, question_id) VALUES(?,?)", bankId, newId);
                copied++;
            }
        }
        if (copied > 0) jt.update("UPDATE bank_store_item SET download_count=download_count+1 WHERE id=? AND is_delete=0", id); // 仅首次下载计 1
        return ok(Map.of("added", true, "familyBankId", bankId, "copied", copied));
    }

    // ---------- FR-S11 我的题库 ----------
    @GetMapping("/mine")
    public Map<String, Object> mine(@RequestHeader("Authorization") String auth,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        long familyId = parentCtx(auth).familyId();
        size = Math.min(size, 100);
        page = Math.max(page, 1);
        String where = " WHERE f.family_id=" + familyId + " AND f.is_delete=0";
        Long total = db.sql("SELECT COUNT(*) FROM family_bank_store f" + where).query(Long.class).single();
        List<Map<String, Object>> list = db.sql("""
                SELECT f.id AS bank_id, f.store_item_id, f.added_at, i.title, i.description, i.subject, i.grade,
                       i.question_count, i.download_count,
                       (SELECT GROUP_CONCAT(t.name) FROM bank_store_item_tag it
                         JOIN bank_store_tag t ON t.id=it.tag_id WHERE it.store_item_id=i.id AND it.is_delete=0 AND t.is_delete=0) AS tags,
                       (SELECT GROUP_CONCAT(a.profile_id) FROM family_bank_assign a
                         WHERE a.store_item_id=f.store_item_id AND a.family_id=f.family_id AND a.status=1 AND a.is_delete=0) AS assigned
                FROM family_bank_store f JOIN bank_store_item i ON i.id=f.store_item_id AND i.is_delete=0""" + where + " ORDER BY f.id DESC LIMIT ? OFFSET ?")
            .param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("familyBankId", rs.getLong("bank_id"));
                m.put("storeId", rs.getLong("store_item_id"));
                m.put("title", rs.getString("title"));
                m.put("description", rs.getString("description"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("questionCount", rs.getInt("question_count"));
                m.put("downloadCount", rs.getInt("download_count"));
                m.put("addedAt", rs.getString("added_at"));
                m.put("tags", rs.getString("tags") == null ? List.of() : List.of(rs.getString("tags").split(",")));
                String assigned = rs.getString("assigned");
                List<Long> profileIds = new ArrayList<>();
                if (assigned != null) for (String s : assigned.split(",")) profileIds.add(Long.parseLong(s.trim()));
                m.put("assignedProfileIds", profileIds);
                return m;
            }).list();
        return ok(Map.of("total", total, "page", page, "list", list));
    }

    // ---------- 子女列表（分配选择用） ----------
    @GetMapping("/profiles")
    public Map<String, Object> profiles(@RequestHeader("Authorization") String auth) {
        long familyId = parentCtx(auth).familyId();
        List<Map<String, Object>> list = db.sql("SELECT id, nickname, grade FROM student_profile WHERE family_id=? AND status=1 AND is_delete=0 ORDER BY id")
            .param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("nickname", rs.getString("nickname"));
                m.put("grade", rs.getInt("grade"));
                return m;
            }).list();
        return ok(Map.of("list", list));
    }

    // ---------- FR-S11 分配（幂等激活；强校验子女归属） ----------
    @PostMapping("/mine/{storeId}/assign")
    @Transactional
    public Map<String, Object> assign(@RequestHeader("Authorization") String auth, @PathVariable Long storeId,
                                      @RequestBody AssignReq req) {
        long familyId = parentCtx(auth).familyId();
        assertAdded(familyId, storeId);
        List<Long> pids = req.profileIds() == null ? List.of() : req.profileIds().stream().distinct().toList();
        if (pids.isEmpty() || pids.size() > 10) throw bad("profileIds 须为1-10个");
        String in = "?,".repeat(pids.size() - 1) + "?";
        Integer owned = db.sql("SELECT COUNT(*) FROM student_profile WHERE family_id=? AND status=1 AND is_delete=0 AND id IN (" + in + ")")
            .params(listOf(familyId, pids)).query(Integer.class).single();
        if (owned != pids.size()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "子女不属于当前家庭");
        for (Long pid : pids)
            jt.update("INSERT INTO family_bank_assign(family_id, store_item_id, profile_id, status) VALUES(?,?,?,1) " +
                "ON DUPLICATE KEY UPDATE status=1, assigned_at=NOW(), is_delete=0", familyId, storeId, pid);
        return ok(Map.of("assigned", pids));
    }

    // ---------- FR-S11 收回分配 ----------
    @DeleteMapping("/mine/{storeId}/assign/{profileId}")
    public Map<String, Object> unassign(@RequestHeader("Authorization") String auth, @PathVariable Long storeId,
                                        @PathVariable Long profileId) {
        long familyId = parentCtx(auth).familyId();
        assertAdded(familyId, storeId);
        jt.update("UPDATE family_bank_assign SET status=0 WHERE family_id=? AND store_item_id=? AND profile_id=? AND is_delete=0",
            familyId, storeId, profileId);
        return ok(Map.of("unassigned", true));
    }

    // ---------- FR-S12 移除（逻辑删除：复制题目进回收站、分配作废、可重新添加） ----------
    @DeleteMapping("/mine/{storeId}")
    @Transactional
    public Map<String, Object> remove(@RequestHeader("Authorization") String auth, @PathVariable Long storeId) {
        long familyId = parentCtx(auth).familyId();
        Long bankId = db.sql("SELECT id FROM family_bank_store WHERE family_id=? AND store_item_id=? AND is_delete=0")
            .params(List.of(familyId, storeId)).query(Long.class).optional().orElse(null);
        if (bankId == null) throw bad("尚未添加该题库");
        // 家庭题库题目逻辑删除（status=3 回收标记 + is_delete=1 隐藏）
        jt.update("UPDATE question SET status=3, is_delete=1 WHERE family_id=? AND id IN (SELECT question_id FROM family_bank_store_question WHERE family_bank_id=? AND is_delete=0)",
            familyId, bankId);
        jt.update("UPDATE family_bank_assign SET is_delete=1 WHERE family_id=? AND store_item_id=? AND is_delete=0", familyId, storeId);
        jt.update("UPDATE family_bank_store_question SET is_delete=1 WHERE family_bank_id=? AND is_delete=0", bankId);
        jt.update("UPDATE family_bank_store SET is_delete=1 WHERE id=? AND is_delete=0", bankId);
        return ok(Map.of("removed", true));
    }

    // ---------- helpers ----------
    private Map<String, Object> itemRow(java.sql.ResultSet rs, boolean withDownload) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("title", rs.getString("title"));
        m.put("description", rs.getString("description"));
        m.put("subject", rs.getInt("subject"));
        m.put("grade", rs.getInt("grade"));
        m.put("questionCount", rs.getInt("question_count"));
        m.put("downloadCount", rs.getInt("download_count"));
        m.put("added", rs.getBoolean("added"));
        String tags = rs.getString("tags");
        List<Map<String, String>> tagList = new ArrayList<>();
        if (tags != null) for (String t : tags.split(",")) {
            String[] kv = t.split(":", 2);
            tagList.add(Map.of("name", kv[0], "color", kv.length > 1 ? kv[1] : "blue"));
        }
        m.put("tags", tagList);
        return m;
    }

    private void assertAdded(long familyId, long storeId) {
        Long bankId = db.sql("SELECT id FROM family_bank_store WHERE family_id=? AND store_item_id=? AND is_delete=0")
            .params(List.of(familyId, storeId)).query(Long.class).optional().orElse(null);
        if (bankId == null) throw bad("尚未添加该题库，无法分配");
    }

    private long insertFamilyBank(long familyId, long storeId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO family_bank_store(family_id, store_item_id) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setLong(2, storeId);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private List<Object> listOf(long first, List<Long> rest) {
        List<Object> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
