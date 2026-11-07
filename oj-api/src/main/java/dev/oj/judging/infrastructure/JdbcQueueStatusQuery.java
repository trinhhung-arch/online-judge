package dev.oj.judging.infrastructure;

import dev.oj.judging.application.published.QueueStatusQuery;
import dev.oj.judging.domain.DomainRules;
import dev.oj.platform.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Repository
public class JdbcQueueStatusQuery implements QueueStatusQuery {

    private static final String DOC = """
            SELECT q.dang_cho, q.dang_cham, q.rejudge_dang_cho, q.cho_lau_nhat, h.may_song
              FROM (SELECT count(*) FILTER (WHERE claimed_at IS NULL)                  AS dang_cho,
                           count(*) FILTER (WHERE claimed_at IS NOT NULL)              AS dang_cham,
                           count(*) FILTER (WHERE claimed_at IS NULL
                                              AND priority = :uuTienRejudge)           AS rejudge_dang_cho,
                           min(enqueued_at) FILTER (WHERE claimed_at IS NULL
                                              AND priority = :uuTienLive)              AS cho_lau_nhat
                      FROM judge_queue) q
             CROSS JOIN (SELECT count(*) AS may_song
                           FROM judge_hosts
                          WHERE enabled AND last_seen_at > :nguongSong) h
            """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AppProperties properties;

    public JdbcQueueStatusQuery(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock,
                                AppProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Override
    public TrangThai doc() {
        OffsetDateTime nguong = OffsetDateTime.ofInstant(
                clock.instant().minus(properties.judge().hostLiveness()), ZoneOffset.UTC);
        return jdbc.sql(DOC)
                .param("uuTienRejudge", DomainRules.PRIORITY_REJUDGE)
                .param("uuTienLive", DomainRules.PRIORITY_LIVE)
                .param("nguongSong", nguong)
                .query((rs, n) -> {
                    OffsetDateTime lauNhat = rs.getObject("cho_lau_nhat", OffsetDateTime.class);
                    return new TrangThai(
                            rs.getInt("dang_cho"),
                            rs.getInt("dang_cham"),
                            rs.getInt("rejudge_dang_cho"),
                            lauNhat == null ? null : lauNhat.toInstant(),
                            rs.getInt("may_song"));
                })
                .single();
    }
}
