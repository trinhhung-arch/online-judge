package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.RejudgeRepository;
import dev.oj.judging.domain.DomainRules;
import dev.oj.judging.domain.RejudgeJob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class JdbcRejudgeRepository implements RejudgeRepository {

    private static final String NHIP = """
            SELECT count(*) FILTER (WHERE priority = :uuTienRejudge)          AS rejudge_dang_cho,
                   min(enqueued_at) FILTER (WHERE priority = :uuTienLive)     AS live_cho_lau_nhat
              FROM judge_queue
             WHERE claimed_at IS NULL
            """;

    private static final String BAI_CUA_DE = """
            SELECT id FROM submissions
             WHERE problem_id = :problemId AND id > :sauId
             ORDER BY id
             LIMIT :gioiHan
            """;

    private static final String DEM_BAI_CUA_DE = """
            SELECT count(*) FROM submissions WHERE problem_id = :problemId
            """;

    private static final String DAY_VAO_HANG_DOI = """
            INSERT INTO judge_queue (submission_id, priority, attempt, enqueued_at)
            SELECT s.id, :uuTien, s.attempt, :bayGio
              FROM submissions s
             WHERE s.id IN (:ids)
            ON CONFLICT (submission_id) DO NOTHING
            RETURNING submission_id
            """;

    private static final String VE_QUEUED = """
            UPDATE submissions SET status = 'QUEUED' WHERE id IN (:ids)
            """;

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public JdbcRejudgeRepository(
            @Qualifier("appJdbcClient") JdbcClient jdbc,
            @Qualifier("appTransactionManager") PlatformTransactionManager txManager,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    @Override
    public RejudgeJob.NhipHangDoi doNhip() {
        return jdbc.sql(NHIP)
                .param("uuTienRejudge", DomainRules.PRIORITY_REJUDGE)
                .param("uuTienLive", DomainRules.PRIORITY_LIVE)
                .query((rs, n) -> {
                    OffsetDateTime lauNhat = rs.getObject("live_cho_lau_nhat", OffsetDateTime.class);
                    return new RejudgeJob.NhipHangDoi(
                            rs.getInt("rejudge_dang_cho"),
                            lauNhat == null ? null : lauNhat.toInstant());
                })
                .single();
    }

    @Override
    public List<Long> baiCuaDe(long problemId, long sauId, int gioiHan) {
        return jdbc.sql(BAI_CUA_DE)
                .param("problemId", problemId)
                .param("sauId", sauId)
                .param("gioiHan", gioiHan)
                .query(Long.class)
                .list();
    }

    @Override
    public int demBaiCuaDe(long problemId) {
        return jdbc.sql(DEM_BAI_CUA_DE).param("problemId", problemId)
                .query(Integer.class).single();
    }

    @Override
    public List<Long> dayVaoHangDoi(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        OffsetDateTime bayGio = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<Long> daVao = tx.execute(status -> {
            List<Long> vao = jdbc.sql(DAY_VAO_HANG_DOI)
                    .param("uuTien", DomainRules.PRIORITY_REJUDGE)
                    .param("bayGio", bayGio)
                    .param("ids", ids)
                    .query(Long.class)
                    .list();
            if (!vao.isEmpty()) {
                jdbc.sql(VE_QUEUED).param("ids", vao).update();
            }
            return vao;
        });
        return daVao == null ? List.of() : daVao;
    }
}
