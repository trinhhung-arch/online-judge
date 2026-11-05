package dev.oj.contests.infrastructure;

import dev.oj.platform.contest.ContestWindowQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

@Repository
public class JdbcContestWindowQuery implements ContestWindowQuery {

    private static final String CONTEST_DANG_CHAY = """
            SELECT c.id
              FROM contest_problems cp
              JOIN contests c ON c.id = cp.contest_id
             WHERE cp.problem_id = :problemId
               AND c.starts_at <= :bayGio
               AND c.ends_at > :bayGio
             ORDER BY c.id
             LIMIT 1
            """;

    private static final String CO_KY_THI_DANG_CHAY = """
            SELECT EXISTS (SELECT 1 FROM contests
                            WHERE starts_at <= :bayGio AND ends_at > :bayGio)
            """;

    private static final String BI_KHOA = """
            SELECT EXISTS (
                SELECT 1
                  FROM contest_problems cp
                  JOIN contests c ON c.id = cp.contest_id
                 WHERE cp.problem_id = :problemId
                   AND c.ends_at > :bayGio
                   AND NOT (
                        c.starts_at <= :bayGio
                        AND (NOT c.registration_required
                             OR EXISTS (SELECT 1 FROM contest_registrations r
                                         WHERE r.contest_id = c.id
                                           AND r.user_id = :userId))))
            """;

    private static final String BI_KHOA_CHO_NGUOI_XEM = """
            SELECT DISTINCT cp.problem_id
              FROM contest_problems cp
              JOIN contests c ON c.id = cp.contest_id
             WHERE c.ends_at > :bayGio
               AND NOT (
                    c.starts_at <= :bayGio
                    AND (NOT c.registration_required
                         OR EXISTS (SELECT 1 FROM contest_registrations r
                                     WHERE r.contest_id = c.id
                                       AND r.user_id = :userId)))
            """;

    private static final String NAM_TRONG_KY_THI_NAO_DO = """
            SELECT EXISTS (SELECT 1 FROM contest_problems WHERE problem_id = :problemId)
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcContestWindowQuery(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public OptionalLong contestDangChayChuaDe(long problemId) {
        return jdbc.sql(CONTEST_DANG_CHAY)
                .param("problemId", problemId)
                .param("bayGio", bayGio())
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public boolean coKyThiDangChay() {
        return Boolean.TRUE.equals(jdbc.sql(CO_KY_THI_DANG_CHAY)
                .param("bayGio", bayGio())
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe) {
        if (laNguoiRaDe) {
            return false;
        }
        return Boolean.TRUE.equals(jdbc.sql(BI_KHOA)
                .param("problemId", problemId)
                .param("bayGio", bayGio())
                .param("userId", userId == null ? 0L : userId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<Long> deBiKhoaChoNguoiXem(Long userId) {
        return jdbc.sql(BI_KHOA_CHO_NGUOI_XEM)
                .param("bayGio", bayGio())
                .param("userId", userId == null ? 0L : userId)
                .query(Long.class)
                .list();
    }

    @Override
    public boolean deNamTrongKyThiNaoDo(long problemId) {
        return Boolean.TRUE.equals(jdbc.sql(NAM_TRONG_KY_THI_NAO_DO)
                .param("problemId", problemId)
                .query(Boolean.class)
                .single());
    }

    private OffsetDateTime bayGio() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
