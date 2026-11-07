package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestFormat.TongKet;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcStandingsRepository implements StandingsRepository {

    private static final String WATERMARK = """
            SELECT COALESCE(max(last_applied_submission_id), 0)
              FROM contest_standings
             WHERE contest_id = :contestId
            """;

    private static final String KET_QUA_THEO_NGUOI = """
            SELECT user_id, problem_id, best_score, attempts_before_score,
                   first_solved_at, solved_submission_id, last_applied_submission_id
              FROM contest_problem_standings
             WHERE contest_id = :contestId AND user_id IN (:userIds)
            """;

    private static final String GHI_KET_QUA_DE = """
            INSERT INTO contest_problem_standings
                (contest_id, user_id, problem_id, best_score, attempts_before_score,
                 first_solved_at, solved_submission_id, last_applied_submission_id)
            VALUES (:contestId, :userId, :problemId, :bestScore, :attempts,
                    :firstSolvedAt, :solvedSubmissionId, :lastApplied)
            ON CONFLICT (contest_id, user_id, problem_id) DO UPDATE
               SET best_score = EXCLUDED.best_score,
                   attempts_before_score = EXCLUDED.attempts_before_score,
                   first_solved_at = EXCLUDED.first_solved_at,
                   solved_submission_id = EXCLUDED.solved_submission_id,
                   last_applied_submission_id = EXCLUDED.last_applied_submission_id
            """;

    private static final String GHI_TONG_KET = """
            INSERT INTO contest_standings
                (contest_id, user_id, total_score, penalty_seconds, solved_count,
                 last_scoring_at, last_applied_submission_id, updated_at)
            VALUES (:contestId, :userId, :tongDiem, :penalty, :soBaiDat,
                    :lanCuoi, :lastApplied, now())
            ON CONFLICT (contest_id, user_id) DO UPDATE
               SET total_score = EXCLUDED.total_score,
                   penalty_seconds = EXCLUDED.penalty_seconds,
                   solved_count = EXCLUDED.solved_count,
                   last_scoring_at = EXCLUDED.last_scoring_at,
                   last_applied_submission_id = EXCLUDED.last_applied_submission_id,
                   updated_at = now()
            """;

    private static final String DA_CHUP = """
            SELECT EXISTS (SELECT 1 FROM contest_standings_frozen WHERE contest_id = :contestId)
            """;

    private static final String CHUP_TONG = """
            INSERT INTO contest_standings_frozen
                (contest_id, user_id, total_score, penalty_seconds, solved_count, last_scoring_at)
            SELECT contest_id, user_id, total_score, penalty_seconds, solved_count, last_scoring_at
              FROM contest_standings
             WHERE contest_id = :contestId
            ON CONFLICT (contest_id, user_id) DO NOTHING
            """;

    private static final String CHUP_THEO_DE = """
            INSERT INTO contest_problem_standings_frozen
                (contest_id, user_id, problem_id, best_score, attempts_before_score,
                 first_solved_at, pending_after_freeze)
            SELECT ps.contest_id, ps.user_id, ps.problem_id, ps.best_score,
                   ps.attempts_before_score, ps.first_solved_at,
                   LEAST((SELECT count(*) FROM submissions s
                           WHERE s.contest_id = ps.contest_id
                             AND s.user_id = ps.user_id
                             AND s.problem_id = ps.problem_id
                             AND s.created_at >= :freezeAt), 32767)
              FROM contest_problem_standings ps
             WHERE ps.contest_id = :contestId
            ON CONFLICT (contest_id, user_id, problem_id) DO NOTHING
            """;

    private static final String XOA_TONG = """
            DELETE FROM contest_standings WHERE contest_id = :contestId
            """;

    private static final String XOA_THEO_DE = """
            DELETE FROM contest_problem_standings WHERE contest_id = :contestId
            """;

    private static final String TONG_KET_DA_LUU = """
            SELECT user_id, total_score, penalty_seconds, solved_count
              FROM contest_standings
             WHERE contest_id = :contestId
            """;

    private static final String GHI_DRIFT = """
            INSERT INTO standings_drift_checks
                (contest_id, rows_checked, rows_mismatched, detail)
            VALUES (:contestId, :soDongKiem, :soDongLech, CAST(:chiTiet AS jsonb))
            """;

    private static final String CAN_SOAT_LECH = """
            SELECT c.id
              FROM contests c
             WHERE c.starts_at <= :bayGio
               AND c.ends_at > :ketThucSau
               AND NOT EXISTS (SELECT 1
                                 FROM standings_drift_checks d
                                WHERE d.contest_id = c.id
                                  AND d.checked_at > :chuaSoatTu)
             ORDER BY c.id
            """;

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcStandingsRepository(@Qualifier("appJdbcClient") JdbcClient jdbc,
                                   ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public long watermark(long contestId) {
        return jdbc.sql(WATERMARK).param("contestId", contestId).query(Long.class).single();
    }

    @Override
    public Map<Long, List<KetQuaDe>> ketQuaTheoNguoi(long contestId, Collection<Long> userIds) {
        Map<Long, List<KetQuaDe>> ketQua = new HashMap<>();
        if (userIds.isEmpty()) {
            return ketQua;
        }
        jdbc.sql(KET_QUA_THEO_NGUOI)
                .param("contestId", contestId)
                .param("userIds", userIds)
                .query((rs, i) -> Map.entry(rs.getLong("user_id"), new KetQuaDe(
                        rs.getLong("problem_id"),
                        rs.getInt("best_score"),
                        rs.getInt("attempts_before_score"),
                        thoiDiem(rs, "first_solved_at"),
                        soLon(rs, "solved_submission_id"),
                        rs.getLong("last_applied_submission_id"))))
                .list()
                .forEach(e -> ketQua.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>())
                        .add(e.getValue()));
        return ketQua;
    }

    @Override
    public void ghiKetQuaDe(long contestId, long userId, KetQuaDe kq) {
        jdbc.sql(GHI_KET_QUA_DE)
                .param("contestId", contestId)
                .param("userId", userId)
                .param("problemId", kq.problemId())
                .param("bestScore", kq.diemCaoNhat())
                .param("attempts", kq.soLanSaiTruocKhiDat())
                .param("firstSolvedAt", luc(kq.datLuc()))
                .param("solvedSubmissionId", kq.baiDatId())
                .param("lastApplied", kq.baiCuoiDaTinh())
                .update();
    }

    @Override
    public void ghiTongKet(long contestId, long userId, TongKet tong, int penaltyGiay,
                           long baiCuoiDaTinh) {
        jdbc.sql(GHI_TONG_KET)
                .param("contestId", contestId)
                .param("userId", userId)
                .param("tongDiem", tong.tongDiem())
                .param("penalty", penaltyGiay)
                .param("soBaiDat", tong.soBaiDat())
                .param("lanCuoi", luc(tong.lanGhiDiemCuoi()))
                .param("lastApplied", baiCuoiDaTinh)
                .update();
    }

    @Override
    public void xoaBangXepHang(long contestId) {
        jdbc.sql(XOA_THEO_DE).param("contestId", contestId).update();
        jdbc.sql(XOA_TONG).param("contestId", contestId).update();
    }

    @Override
    public boolean daChupDongBang(long contestId) {
        return Boolean.TRUE.equals(jdbc.sql(DA_CHUP)
                .param("contestId", contestId).query(Boolean.class).single());
    }

    @Override
    public void chupDongBang(long contestId, Instant freezeAt) {
        jdbc.sql(CHUP_TONG).param("contestId", contestId).update();
        jdbc.sql(CHUP_THEO_DE)
                .param("contestId", contestId)
                .param("freezeAt", luc(freezeAt))
                .update();
    }

    @Override
    public Map<Long, DiemDaLuu> tongKetDaLuu(long contestId) {
        Map<Long, DiemDaLuu> ketQua = new HashMap<>();
        jdbc.sql(TONG_KET_DA_LUU)
                .param("contestId", contestId)
                .query((rs, i) -> Map.entry(rs.getLong("user_id"), new DiemDaLuu(
                        rs.getInt("total_score"),
                        rs.getInt("penalty_seconds"),
                        rs.getInt("solved_count"))))
                .list()
                .forEach(e -> ketQua.put(e.getKey(), e.getValue()));
        return ketQua;
    }

    @Override
    public void ghiDrift(long contestId, int soDongKiem, int soDongLech,
                         Map<String, Object> chiTiet) {
        jdbc.sql(GHI_DRIFT)
                .param("contestId", contestId)
                .param("soDongKiem", soDongKiem)
                .param("soDongLech", soDongLech)
                .param("chiTiet", json.writeValueAsString(chiTiet))
                .update();
    }

    private static OffsetDateTime luc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant thoiDiem(ResultSet rs, String cot) throws SQLException {
        OffsetDateTime value = rs.getObject(cot, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Long soLon(ResultSet rs, String cot) throws SQLException {
        long value = rs.getLong(cot);
        return rs.wasNull() ? null : value;
    }

    @Override
    public List<Long> canSoatLech(Instant bayGio, Instant ketThucSau, Instant chuaSoatTu) {
        return jdbc.sql(CAN_SOAT_LECH)
                .param("bayGio", luc(bayGio))
                .param("ketThucSau", luc(ketThucSau))
                .param("chuaSoatTu", luc(chuaSoatTu))
                .query(Long.class)
                .list();
    }

}
