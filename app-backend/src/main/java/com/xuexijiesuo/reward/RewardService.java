package com.xuexijiesuo.reward;

import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 激励发放（W6，FR-19~21）。
 * 积分口径：答对 1 题 = 10 × 难度系数(L1=0.6~L5=1.4)；超门槛奖 +30；单日获取上限 500（防刷）。
 * 打卡口径：达标即打卡（自然日 ≥1 次达标任务）；连续 7 天 → 勋章 + 积分。
 * 勋章：幂等授予（INSERT IGNORE），首批 perfect / streak7。
 */
@Service
public class RewardService {

    private static final Map<Integer, Double> FACTOR =
        Map.of(1, 0.6, 2, 0.8, 3, 1.0, 4, 1.2, 5, 1.4);

    private final JdbcClient db;
    private final JdbcTemplate jt;

    public RewardService(JdbcClient db, JdbcTemplate jt) {
        this.db = db;
        this.jt = jt;
    }

    /** 判分达标后由判分服务调用（QuizController.submit），发放积分+打卡+勋章 */
    public Map<String, Object> grantTaskReward(long profileId, int correct,
                                               List<Integer> correctDifficulties, int score, int threshold) {
        int base = (int) Math.round(correctDifficulties.stream()
            .mapToDouble(d -> 10 * FACTOR.getOrDefault(d, 1.0)).sum());
        int bonus = score >= threshold ? 30 : 0; // 超门槛差额奖励（FR-19）
        int reward = base + bonus;

        // 单日获取上限 500（FR-19 防刷）
        int todayPositive = db.sql(
                "SELECT COALESCE(SUM(delta),0) FROM point_flow WHERE profile_id=? AND delta>0 AND is_delete=0 AND DATE(created_at)=CURDATE()")
            .param(profileId).query(Integer.class).single();
        reward = Math.min(reward, Math.max(0, 500 - todayPositive));

        int added = 0;
        if (reward > 0) {
            added = reward;
            jt.update("INSERT INTO point_account(profile_id, balance) VALUES(?,?) ON DUPLICATE KEY UPDATE balance=balance+?",
                profileId, reward, reward);
            jt.update("INSERT INTO point_flow(profile_id, delta, reason, ref_id) VALUES(?,1,1,?)", profileId, "task:" + score);
        }

        // 打卡（FR-21：达标即打卡，幂等）
        int checkinNew = jt.update(
            "INSERT IGNORE INTO checkin_record(profile_id, checkin_date) VALUES(?,CURDATE())", profileId);
        int streak = calcStreak(profileId);

        // 勋章（幂等授予）
        List<String> newMedals = new ArrayList<>();
        if (score == 100) newMedals.addAll(grantMedal(profileId, "perfect", "单次满分"));
        if (streak >= 7) {
            List<String> m = grantMedal(profileId, "streak7", "连续打卡7天");
            if (!m.isEmpty()) { // 首次达成才发奖励（勋章幂等保证一次性，FR-21：+200 积分）
                newMedals.addAll(m);
                jt.update("INSERT INTO point_account(profile_id, balance) VALUES(?,200) ON DUPLICATE KEY UPDATE balance=balance+200",
                    profileId);
                jt.update("INSERT INTO point_flow(profile_id, delta, reason, ref_id) VALUES(?,200,3,?)", profileId, "streak7");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("points", added);
        data.put("streak", streak);
        data.put("checkinNew", checkinNew > 0);
        data.put("newMedals", newMedals);
        return data;
    }

    /** 连续打卡天数（从今天/昨天回溯连续自然日） */
    public int calcStreak(long profileId) {
        List<LocalDate> dates = db.sql(
                "SELECT checkin_date FROM checkin_record WHERE profile_id=? AND is_delete=0 ORDER BY checkin_date DESC LIMIT 400")
            .param(profileId).query((rs, i) -> rs.getDate("checkin_date").toLocalDate()).list();
        if (dates.isEmpty()) return 0;
        LocalDate cur = LocalDate.now();
        if (!dates.get(0).equals(cur)) {
            if (!dates.get(0).equals(cur.minusDays(1))) return 0; // 今天没打且昨天也没打 → 断
            cur = cur.minusDays(1);
        }
        int streak = 0;
        for (LocalDate d : dates) {
            if (d.equals(cur)) { streak++; cur = cur.minusDays(1); }
            else if (d.isBefore(cur)) break;
        }
        return streak;
    }

    /** 幂等授予勋章，返回新获得则含 code */
    public List<String> grantMedal(long profileId, String code, String name) {
        int n = jt.update("INSERT IGNORE INTO profile_medal(profile_id, medal_code) VALUES(?,?)", profileId, code);
        return n > 0 ? List.of(code) : List.of();
    }
}
