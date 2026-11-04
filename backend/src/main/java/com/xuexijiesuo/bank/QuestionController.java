package com.xuexijiesuo.bank;

import tools.jackson.databind.ObjectMapper;
import com.xuexijiesuo.account.TokenService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 题库管理（FR-01~04，家长 scope）。
 * W3 开发版：机审字段 review_status 默认 0 通过——接入内容审核 API（依赖 D-02）后先审后入库。
 */
@RestController
@RequestMapping("/api/bank")
public class QuestionController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final ObjectMapper om = new ObjectMapper();

    public QuestionController(JdbcClient db, JdbcTemplate jt, TokenService tokens) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
    }

    public record QUpsert(Integer subject, Integer grade, Integer difficulty, Integer qType,
                          String stem, List<String> options, String answer, String analysis) {}

    private record Ctx(long familyId) {}

    // ---------- 校验 ----------
    private List<String> validate(QUpsert q) {
        List<String> errs = new ArrayList<>();
        if (q.subject() == null || q.subject() < 1 || q.subject() > 10) errs.add("科目非法(1-10)");
        if (q.grade() == null || q.grade() < 1 || q.grade() > 12) errs.add("年级非法(1-12)");
        if (q.difficulty() == null || q.difficulty() < 1 || q.difficulty() > 5) errs.add("难度非法(L1-L5)");
        if (q.qType() == null || q.qType() < 1 || q.qType() > 4) errs.add("题型非法(1单选2多选3判断4填空)");
        if (q.stem() == null || q.stem().isBlank()) errs.add("题干必填");
        if (q.answer() == null || q.answer().isBlank()) errs.add("答案必填");
        if (q.qType() != null && q.qType() <= 2 && (q.options() == null || q.options().size() < 2)) errs.add("选择类题目至少2个选项");
        return errs;
    }

    private Ctx parentCtx(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"parent".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅家长可管理题库"); // FR-10
        return new Ctx(c.familyId());
    }

    // ---------- 单题创建（FR-01） ----------
    @PostMapping("/questions")
    public Map<String, Object> create(@RequestHeader("Authorization") String auth, @RequestBody QUpsert q) {
        long familyId = parentCtx(auth).familyId();
        List<String> errs = validate(q);
        if (!errs.isEmpty()) throw bad(String.join("; ", errs));
        long id = insertQuestion(familyId, q);
        return ok(Map.of("id", id));
    }

    // ---------- 批量导入（FR-02，≤500 行，逐行校验，错误行不阻断合格行） ----------
    public record ImportReq(List<QUpsert> items) {}

    @PostMapping("/questions/import")
    public Map<String, Object> importQuestions(@RequestHeader("Authorization") String auth, @RequestBody ImportReq req) {
        long familyId = parentCtx(auth).familyId();
        if (req.items() == null || req.items().isEmpty()) throw bad("导入内容为空");
        if (req.items().size() > 500) throw bad("单批最多500行");
        int inserted = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        List<QUpsert> items = req.items();
        for (int i = 0; i < items.size(); i++) {
            List<String> errs = validate(items.get(i));
            if (!errs.isEmpty()) {
                failed.add(Map.of("row", i + 1, "reason", String.join("; ", errs)));
                continue;
            }
            insertQuestion(familyId, items.get(i));
            inserted++;
        }
        return ok(Map.of("total", items.size(), "inserted", inserted, "failed", failed));
    }

    // ---------- 分类筛选（FR-03，三维组合+分页） ----------
    @GetMapping("/questions")
    public Map<String, Object> list(@RequestHeader("Authorization") String auth,
                                    @RequestParam(required = false) Integer subject,
                                    @RequestParam(required = false) Integer grade,
                                    @RequestParam(required = false) Integer difficulty,
                                    @RequestParam(defaultValue = "1") Integer status,
                                    @RequestParam(defaultValue = "1") Integer page,
                                    @RequestParam(defaultValue = "20") Integer size) {
        long familyId = parentCtx(auth).familyId();
        size = Math.min(size, 100);
        StringBuilder where = new StringBuilder(" WHERE (family_id=? OR (family_id IS NULL AND source=1))");
        List<Object> params = new ArrayList<>(List.of(familyId));
        if (subject != null) { where.append(" AND subject=?"); params.add(subject); }
        if (grade != null) { where.append(" AND grade=?"); params.add(grade); }
        if (difficulty != null) { where.append(" AND difficulty=?"); params.add(difficulty); }
        where.append(" AND status=?"); params.add(status);
        Long total = db.sql("SELECT COUNT(*) FROM question" + where).params(params).query(Long.class).single();
        List<Map<String, Object>> list = db.sql("SELECT id, family_id, subject, grade, difficulty, q_type, stem, options_json, answer, source, status, review_status, created_at FROM question"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?")
            .params(params).param(size).param((page - 1) * size)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("subject", rs.getInt("subject"));
                m.put("grade", rs.getInt("grade"));
                m.put("difficulty", rs.getInt("difficulty"));
                m.put("qType", rs.getInt("q_type"));
                m.put("stem", rs.getString("stem"));
                m.put("options", rs.getString("options_json"));
                m.put("answer", rs.getString("answer"));
                m.put("source", rs.getInt("source") == 1 ? "内置" : "自建");
                m.put("status", rs.getInt("status"));
                return m;
            }).list();
        return ok(Map.of("total", total, "page", page, "list", list));
    }

    // ---------- 回收站（FR-04：删除进回收站30天，历史作答快照不受影响） ----------
    @DeleteMapping("/questions/{id}")
    public Map<String, Object> recycle(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        long familyId = parentCtx(auth).familyId();
        int n = jt.update("UPDATE question SET status=3 WHERE id=? AND family_id=? AND status IN (0,1)", id, familyId);
        if (n == 0) throw bad("题目不存在或不可删除");
        return ok(Map.of("recycled", true));
    }

    @PostMapping("/questions/{id}/restore")
    public Map<String, Object> restore(@RequestHeader("Authorization") String auth, @PathVariable Long id) {
        long familyId = parentCtx(auth).familyId();
        int n = jt.update("UPDATE question SET status=1 WHERE id=? AND family_id=? AND status=3", id, familyId);
        if (n == 0) throw bad("仅回收站内题目可恢复");
        return ok(Map.of("restored", true));
    }

    // ---------- helpers ----------
    private long insertQuestion(long familyId, QUpsert q) {
        KeyHolder kh = new GeneratedKeyHolder();
        final String optionsJson;
        try {
            optionsJson = q.options() == null ? null : om.writeValueAsString(q.options());
        } catch (Exception e) {
            throw new IllegalStateException("选项序列化失败", e);
        }
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO question(family_id, subject, grade, difficulty, q_type, stem, options_json, answer, analysis, source, status, review_status) VALUES(?,?,?,?,?,?,?,?,?,2,1,0)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, familyId);
            ps.setInt(2, q.subject());
            ps.setInt(3, q.grade());
            ps.setInt(4, q.difficulty());
            ps.setInt(5, q.qType());
            ps.setString(6, q.stem());
            ps.setString(7, optionsJson);
            ps.setString(8, q.answer());
            ps.setString(9, q.analysis());
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
