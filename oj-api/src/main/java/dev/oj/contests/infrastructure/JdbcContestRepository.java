package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.contests.domain.ContestsException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * Bảng {@code contests}, {@code contest_problems}, {@code contest_registrations} (V7).
 * Pool {@code app}.
 */
@Repository
public class JdbcContestRepository implements ContestRepository {

    private static final String CHON = """
            SELECT id, slug, title, format, starts_at, ends_at, freeze_at, unfrozen_at,
                   penalty_minutes, registration_required, reveal_after_end, created_by
              FROM contests
             WHERE id = :id
            """;

    private static final String CHON_THEO_SLUG = """
            SELECT id, slug, title, format, starts_at, ends_at, freeze_at, unfrozen_at,
                   penalty_minutes, registration_required, reveal_after_end, created_by
              FROM contests
             WHERE lower(slug) = lower(:slug)
            """;

    /**
     * Gồm cả kỳ thi <b>vừa kết thúc</b> trong khoảng ân hạn.
     *
     * <p>Bài nộp ở giây cuối vẫn đang chấm khi chuông reo, và verdict tới sau vài giây. Cắt
     * đúng {@code ends_at} là bỏ rơi chúng — và chúng thường là những bài quyết định thứ hạng.
     */
    private static final String CAN_CAP_NHAT = """
            SELECT id, slug, title, format, starts_at, ends_at, freeze_at, unfrozen_at,
                   penalty_minutes, registration_required, reveal_after_end, created_by
              FROM contests
             WHERE starts_at <= :bayGio
               AND ends_at > :moc
             ORDER BY id
            """;

    private static final String CAN_DONG_BANG = """
            SELECT c.id, c.slug, c.title, c.format, c.starts_at, c.ends_at, c.freeze_at,
                   c.unfrozen_at, c.penalty_minutes, c.registration_required,
                   c.reveal_after_end, c.created_by
              FROM contests c
             WHERE c.freeze_at IS NOT NULL
               AND c.freeze_at <= :bayGio
               AND NOT EXISTS (SELECT 1 FROM contest_standings_frozen f
                                WHERE f.contest_id = c.id)
             ORDER BY c.id
            """;

    private static final String TAO = """
            INSERT INTO contests (slug, title, format, starts_at, ends_at, freeze_at,
                                  penalty_minutes, registration_required, reveal_after_end,
                                  created_by)
            VALUES (:slug, :title, :format, :startsAt, :endsAt, :freezeAt,
                    :penaltyMinutes, :registrationRequired, :revealAfterEnd, :createdBy)
            RETURNING id
            """;

    private static final String THEM_DE = """
            INSERT INTO contest_problems (contest_id, problem_id, label, ordinal, points)
            VALUES (:contestId, :problemId, :label, :ordinal, :points)
            ON CONFLICT (contest_id, problem_id) DO UPDATE
               SET label = EXCLUDED.label,
                   ordinal = EXCLUDED.ordinal,
                   points = EXCLUDED.points
            """;

    private static final String DE_CUA = """
            SELECT problem_id, label, ordinal, points
              FROM contest_problems
             WHERE contest_id = :contestId
             ORDER BY ordinal
            """;

    private static final String DA_DANG_KY = """
            SELECT EXISTS (SELECT 1 FROM contest_registrations
                            WHERE contest_id = :contestId AND user_id = :userId)
            """;

    private static final String DANG_KY = """
            INSERT INTO contest_registrations (contest_id, user_id, registered_at)
            VALUES (:contestId, :userId, :luc)
            """;

    private static final String CONG_BO = """
            UPDATE contests SET unfrozen_at = :luc WHERE id = :id AND unfrozen_at IS NULL
            """;

    private final JdbcClient jdbc;

    public JdbcContestRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Contest> timTheoId(long contestId) {
        return jdbc.sql(CHON).param("id", contestId).query(MAPPER).optional();
    }

    @Override
    public Optional<Contest> timTheoSlug(String slug) {
        return jdbc.sql(CHON_THEO_SLUG).param("slug", slug).query(MAPPER).optional();
    }

    @Override
    public long tao(ContestMoi c) {
        try {
            return jdbc.sql(TAO)
                    .param("slug", c.slug())
                    .param("title", c.title())
                    .param("format", c.format())
                    .param("startsAt", luc(c.startsAt()))
                    .param("endsAt", luc(c.endsAt()))
                    .param("freezeAt", luc(c.freezeAt()))
                    .param("penaltyMinutes", c.penaltyMinutes())
                    .param("registrationRequired", c.registrationRequired())
                    .param("revealAfterEnd", c.revealAfterEnd())
                    .param("createdBy", c.createdBy())
                    .query(Long.class)
                    .single();
        } catch (DuplicateKeyException e) {
            throw ContestsException.khongHopLe("contest.slug_da_ton_tai",
                    "Mã kỳ thi này đã được dùng. Hãy chọn mã khác.");
        }
    }

    @Override
    public void themDe(long contestId, long problemId, String label, int ordinal, int points) {
        try {
            jdbc.sql(THEM_DE)
                    .param("contestId", contestId)
                    .param("problemId", problemId)
                    .param("label", label)
                    .param("ordinal", ordinal)
                    .param("points", points)
                    .update();
        } catch (DuplicateKeyException e) {
            // UNIQUE (contest_id, label) — hai đề cùng nhãn 'A' thì bảng xếp hạng có hai cột
            // trùng tên và không ai biết cột nào là đề nào.
            throw ContestsException.khongHopLe("contest.nhan_de_trung",
                    "Nhãn đề này đã được dùng trong kỳ thi.");
        }
    }

    @Override
    public List<DeCuaContest> deCua(long contestId) {
        return jdbc.sql(DE_CUA)
                .param("contestId", contestId)
                .query((rs, i) -> new DeCuaContest(
                        rs.getLong("problem_id"), rs.getString("label"),
                        rs.getInt("ordinal"), rs.getInt("points")))
                .list();
    }

    @Override
    public boolean daDangKy(long contestId, long userId) {
        return Boolean.TRUE.equals(jdbc.sql(DA_DANG_KY)
                .param("contestId", contestId).param("userId", userId)
                .query(Boolean.class).single());
    }

    @Override
    public void dangKy(long contestId, long userId, Instant luc) {
        try {
            jdbc.sql(DANG_KY)
                    .param("contestId", contestId)
                    .param("userId", userId)
                    .param("luc", luc(luc))
                    .update();
        } catch (DuplicateKeyException e) {
            throw ContestsException.daDangKy();
        }
    }

    @Override
    public List<Contest> canCapNhat(Instant bayGio, Duration anHan) {
        return jdbc.sql(CAN_CAP_NHAT)
                .param("bayGio", luc(bayGio))
                .param("moc", luc(bayGio.minus(anHan)))
                .query(MAPPER)
                .list();
    }

    @Override
    public List<Contest> canDongBang(Instant bayGio) {
        return jdbc.sql(CAN_DONG_BANG).param("bayGio", luc(bayGio)).query(MAPPER).list();
    }

    @Override
    public void congBo(long contestId, Instant luc) {
        jdbc.sql(CONG_BO).param("luc", luc(luc)).param("id", contestId).update();
    }

    private static OffsetDateTime luc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static final RowMapper<Contest> MAPPER = (rs, i) -> new Contest(
            rs.getLong("id"),
            rs.getString("slug"),
            rs.getString("title"),
            ContestFormats.tuMa(rs.getString("format")),
            thoiDiem(rs, "starts_at"),
            thoiDiem(rs, "ends_at"),
            thoiDiem(rs, "freeze_at"),
            thoiDiem(rs, "unfrozen_at"),
            rs.getInt("penalty_minutes"),
            rs.getBoolean("registration_required"),
            rs.getBoolean("reveal_after_end"),
            rs.getLong("created_by"));

    private static Instant thoiDiem(ResultSet rs, String cot) throws SQLException {
        OffsetDateTime value = rs.getObject(cot, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
