package com.xuexijiesuo.quiz;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.xuexijiesuo.account.TokenService;
import com.xuexijiesuo.reward.RewardService;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 出题引擎 v1 + 服务端判分（FR-07/12，学生 scope）。
 * 出题策略 v1：年级匹配 → 难度 L2~L4 以 L3 为基准（能力值算法 TODO：首月数据回测后迭代）
 * → 排除该学生近 7 天已答题（FR-07 去重）→ 随机抽取。
 * 判分红线：服务端唯一判分；作答幂等（task result 条件更新）；异常快答判废（T-06）。
 */
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final JdbcClient db;
    private final JdbcTemplate jt;
    private final TokenService tokens;
    private final com.xuexijiesuo.bank.ConfigController configs;
    private final ObjectMapper om = new ObjectMapper();
    private final RewardService rewards;

    public QuizController(JdbcClient db, JdbcTemplate jt, TokenService tokens, com.xuexijiesuo.bank.ConfigController configs, RewardService rewards) {
        this.db = db;
        this.jt = jt;
        this.tokens = tokens;
        this.configs = configs;
        this.rewards = rewards;
    }

    public record StartReq(Integer count, Integer durationMin, Integer threshold) {}
    public record AnswerItem(Long questionId, String answer, Integer costMs, Integer switchCount) {}
    public record SubmitReq(Long taskId, List<AnswerItem> answers) {}

    // ---------- 出题（FR-07 配置化 FR-08 + 错题重练 FR-09） ----------
    @PostMapping("/start")
    public Map<String, Object> start(@RequestHeader("Authorization") String auth) {
        TokenService.TokenClaims c = studentClaims(auth);
        long profileId = c.profileId() == null ? throwNoProfile() : c.profileId();
        Long familyId = db.sql("SELECT family_id FROM student_profile WHERE id=?").param(profileId)
            .query(Long.class).optional().orElseThrow(() -> bad("学生档案不存在"));
        Map<String, Object> cfg = configs.loadConfig(familyId); // DIY 配置为唯一来源(FR-08)，不再接受请求参数
        int count = (int) cfg.get("count");
        int durationMin = (int) cfg.get("durationMin");
        int threshold = (int) cfg.get("threshold");

        Integer grade = db.sql("SELECT grade FROM student_profile WHERE id=?").param(profileId)
            .query(Integer.class).optional().orElseThrow(() -> bad("学生档案不存在"));

        List<Map<String, Object>> picked = new ArrayList<>();
        Set<Long> pickedIds = new HashSet<>();

        // 错题重练池（FR-09）：3 天前答错的题优先，占本次任务 ≤30%
        int wrongCap = Math.max(1, count * 3 / 10);
        List<Long> wrongIds = db.sql("""
                SELECT DISTINCT ar.question_id FROM answer_record ar
                JOIN quiz_task t ON ar.task_id = t.id
                WHERE t.profile_id=? AND ar.is_correct=0
                  AND ar.created_at <= NOW() - INTERVAL 3 DAY
                  AND ar.created_at > NOW() - INTERVAL 30 DAY
                LIMIT ?
                """)
            .param(profileId).param(wrongCap).query(Long.class).list();
        for (Long qid : wrongIds) {
            if (picked.size() >= wrongCap) break;
            var q = fetchQuestion(qid, grade, familyId);
            if (q != null) { picked.add(q); pickedIds.add(qid); }
        }

        // 新题：排除近 7 天已答（FR-07 去重）与已选错题
        List<Map<String, Object>> fresh = db.sql("""
                SELECT id, subject, difficulty, q_type, stem, options_json FROM question
                WHERE status=1 AND review_status=0 AND grade=? AND (family_id=? OR source=1)
                  AND id NOT IN (
                    SELECT ar.question_id FROM answer_record ar
                    JOIN quiz_task t ON ar.task_id=t.id
                    WHERE t.profile_id=? AND t.created_at > NOW() - INTERVAL 7 DAY)
                ORDER BY RAND() LIMIT ?
                """)
            .param(grade).param(familyId).param(profileId).param(count - picked.size())
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("subject", rs.getInt("subject"));
                m.put("qType", rs.getInt("q_type"));
                m.put("stem", rs.getString("stem"));
                m.put("options", rs.getString("options_json"));
                return m;
            }).list();
        for (Map<String, Object> q : fresh) {
            if (picked.size() >= count) break;
            long id = ((Number) q.get("id")).longValue();
            if (pickedIds.add(id)) picked.add(q);
        }
        if (picked.isEmpty())
            throw bad("题库不足：该年级暂无可用新题（7 天内已答完且无错题可重练），请家长补充题库(E-03)");
        // 部分出题原则：错题重练命中但新题不足时，按实际可用题数出卷，不硬性凑满配置题数

        String idempotentKey = profileId + ":" + UUID.randomUUID().toString().replace("-", "");
        String configSnapshot;
        String questionIds;
        try {
            configSnapshot = om.writeValueAsString(Map.of("count", count, "durationMin", durationMin, "threshold", threshold));
            questionIds = om.writeValueAsString(picked.stream().map(m -> ((Number) m.get("id")).longValue()).toList());
        } catch (Exception e) { throw new IllegalStateException(e); }

        KeyHolder kh = new GeneratedKeyHolder();
        jt.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO quiz_task(profile_id, config_snapshot, question_ids, threshold, result, idempotent_key) VALUES(?,?,?,?,0,?)",
                Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, profileId);
            ps.setString(2, configSnapshot);
            ps.setString(3, questionIds);
            ps.setInt(4, threshold);
            ps.setString(5, idempotentKey);
            return ps;
        }, kh);
        long taskId = kh.getKey().longValue();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("durationMin", durationMin);
        data.put("threshold", threshold); // 仅任务返回一次，列表/配置接口脱敏(FR-10)
        data.put("questions", picked);
        return ok(data);
    }

    /** 取一道仍在上架且年级匹配的题（错题重练用），不存在返回 null */
    private Map<String, Object> fetchQuestion(long qid, Integer grade, long familyId) {
        return db.sql("SELECT id, subject, difficulty, q_type, stem, options_json FROM question WHERE id=? AND status=1 AND review_status=0 AND grade=? AND (family_id=? OR source=1)")
            .param(qid).param(grade).param(familyId)
            .query((rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getLong("id"));
                m.put("subject", rs.getInt("subject"));
                m.put("qType", rs.getInt("q_type"));
                m.put("stem", rs.getString("stem"));
                m.put("options", rs.getString("options_json"));
                return m;
            }).optional().orElse(null);
    }

    // ---------- 服务端判分（FR-12） ----------
    @PostMapping("/submit")
    public Map<String, Object> submit(@RequestHeader("Authorization") String auth, @RequestBody SubmitReq req) {
        TokenService.TokenClaims c = studentClaims(auth);
        long profileId = c.profileId() == null ? throwNoProfile() : c.profileId();

        Map<String, Object> task = db.sql("SELECT id, profile_id, question_ids, threshold, result FROM quiz_task WHERE id=?")
            .param(req.taskId()).query((rs, i) -> {
                Map<String, Object> m = new HashMap<>();
                m.put("profileId", rs.getLong("profile_id"));
                m.put("questionIds", rs.getString("question_ids"));
                m.put("threshold", rs.getInt("threshold"));
                m.put("result", rs.getInt("result"));
                return m;
            }).optional().orElseThrow(() -> bad("任务不存在"));
        if (!Objects.equals(((Number) task.get("profileId")).longValue(), profileId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "任务不属于该学生");
        if (((Number) task.get("result")).intValue() != 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已判分（幂等保护）");

        List<Long> qids = parseIds((String) task.get("questionIds"));
        // 拉取标准答案
        Map<Long, String> answers = new HashMap<>();
        Map<Long, String> analysis = new HashMap<>();
        Map<Long, String> stems = new HashMap<>();
        Map<Long, Integer> diffs = new HashMap<>();
        Map<Long, String> optionsJson = new HashMap<>();
        for (Long qid : qids) {
            db.sql("SELECT stem, answer, analysis, difficulty, options_json FROM question WHERE id=?").param(qid)
                .query((rs, i) -> {
                    answers.put(qid, rs.getString("answer"));
                    analysis.put(qid, rs.getString("analysis"));
                    stems.put(qid, rs.getString("stem"));
                    diffs.put(qid, rs.getInt("difficulty"));
                    optionsJson.put(qid, rs.getString("options_json"));
                    return qid;
                }).optional().orElseThrow(() -> bad("题目缺失:" + qid));
        }

        int correct = 0, abnormalFast = 0;
        List<Map<String, Object>> wrong = new ArrayList<>();
        for (AnswerItem a : req.answers()) {
            String std = answers.get(a.questionId());
            if (std == null) continue;
            boolean isCorrect = matchAnswer(a.answer(), std, optionsJson.get(a.questionId()));
            int cost = a.costMs() == null ? 0 : a.costMs();
            int sw = a.switchCount() == null ? 0 : a.switchCount();
            if (isCorrect) correct++;
            if (isCorrect && cost < 2000) abnormalFast++; // T-06 异常快答
            if (!isCorrect) {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("questionId", a.questionId());
                w.put("stem", stems.get(a.questionId()));
                w.put("yourAnswer", a.answer());
                w.put("correctAnswer", std);
                w.put("analysis", analysis.get(a.questionId()));
                wrong.add(w);
            }
            jt.update("INSERT INTO answer_record(task_id, question_id, answer, is_correct, cost_ms, switch_count) VALUES(?,?,?,?,?,?)",
                req.taskId(), a.questionId(), a.answer(), isCorrect ? 1 : 0, cost, sw);
        }

        int total = qids.size();
        int threshold = ((Number) task.get("threshold")).intValue();
        int result; Integer score;
        if (abnormalFast >= 3) {          // 判废（T-06），家长告警事件 TODO
            result = 3; score = null;
        } else {
            score = (int) Math.round(correct * 100.0 / Math.max(total, 1));
            result = score >= threshold ? 1 : 2;
        }
        int updated = jt.update("UPDATE quiz_task SET score=?, result=?, abnormal_flag=?, submitted_at=NOW() WHERE id=? AND result=0",
            score, result, abnormalFast >= 3 ? 1 : 0, req.taskId());
        if (updated == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "任务已判分（并发幂等保护）");
        if (result == 3) { // T-06 判废 → 双写风控事件与家长告警（FR-13/14）
            jt.update("INSERT INTO risk_event(family_id, profile_id, type, detail) VALUES(?,?, 'T-06', ?)",
                c.familyId(), profileId, "{\"reason\":\"abnormalFast\",\"count\":" + abnormalFast + ",\"taskId\":" + req.taskId() + "}");
            jt.update("INSERT INTO alarm(family_id, type, title, content) VALUES(?,?,?,?)",
                c.familyId(), "RISK_T-06", "异常快答判废",
                "本次作答检测到异常快答 " + abnormalFast + " 题，已判废（任务" + req.taskId() + "）");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("score", score);
        data.put("result", result); // 1达标 2未达标 3作废
        if (result == 1) { // W6: 达标自动发激励（积分+打卡+勋章，FR-19~21）
            List<Integer> correctDiffs = new ArrayList<>();
            for (AnswerItem a : req.answers()) {
                String std = answers.get(a.questionId());
                if (std != null && matchAnswer(a.answer(), std, optionsJson.get(a.questionId()))) {
                    Integer d = diffs.get(a.questionId());
                    correctDiffs.add(d == null ? 3 : d);
                }
            }
            data.put("reward", rewards.grantTaskReward(profileId, correct, correctDiffs, score, threshold));
        }
        data.put("threshold", threshold);
        data.put("correct", correct);
        data.put("total", total);
        data.put("wrong", wrong);
        return ok(data);
    }

    // ---------- helpers ----------
    private TokenService.TokenClaims studentClaims(String auth) {
        if (auth == null || !auth.startsWith("Bearer ")) throw bad("缺少 token");
        TokenService.TokenClaims c;
        try { c = tokens.verify(auth.substring(7)); }
        catch (IllegalStateException e) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage()); }
        if (!"student".equals(c.scope())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅学生可答题");
        return c;
    }

    private long throwNoProfile() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "学生身份缺少档案(先完成设备绑定)");
    }

    private List<Long> parseIds(String json) {
        try {
            return om.readValue(json, new TypeReference<List<Long>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toUpperCase();
    }

    /**
     * 判分兼容：题库标准答案可能存选项字母(A~H)或选项文本，学生端提交选项文本。
     * 先按原文比较，不等时借助 options_json 做字母↔文本双向映射。
     */
    private boolean matchAnswer(String student, String std, String optionsRaw) {
        if (norm(student).equals(norm(std))) return true;
        List<String> opts = parseOptions(optionsRaw);
        if (opts.isEmpty()) return false;
        if (std != null && std.trim().length() == 1) {
            int idx = "ABCDEFGH".indexOf(std.trim().toUpperCase());
            if (idx >= 0 && idx < opts.size() && norm(student).equals(norm(opts.get(idx)))) return true;
        }
        if (student != null && student.trim().length() == 1) {
            int idx = "ABCDEFGH".indexOf(student.trim().toUpperCase());
            if (idx >= 0 && idx < opts.size() && norm(std).equals(norm(opts.get(idx)))) return true;
        }
        return false;
    }

    private List<String> parseOptions(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return om.readValue(raw, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<String, Object> ok(Map<String, Object> data) {
        return Map.of("code", 0, "msg", "ok", "data", data);
    }
}
