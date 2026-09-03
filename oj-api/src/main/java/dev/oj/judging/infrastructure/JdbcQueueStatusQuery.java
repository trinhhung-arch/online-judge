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

/**
 * Hiện thực {@link QueueStatusQuery} — truy vấn 12 của {@code duong_nong.sql}, Bước 6.11.
 *
 * <h2>Một câu, hai bảng, không {@code JOIN}</h2>
 * Số liệu hàng đợi và số máy chấm sống đến từ hai bảng không liên quan nhau. Nối chúng bằng
 * một {@code CROSS JOIN} với subquery giữ nguyên một lượt round-trip mà không tạo ra quan hệ
 * giả nào — và endpoint công khai này có thể bị gọi rất nhiều lần (trang trạng thái tự làm
 * mới), nên một lượt thay vì hai là đáng.
 *
 * <h2>★ "Máy chấm sống" ở đây mịn tới đâu, và vì sao nói ra</h2>
 * {@code judge_hosts.last_seen_at} chỉ được cập nhật bởi endpoint {@code benchmark}, mà worker
 * gọi <b>15 phút một lần</b> ({@code oj.worker.sandbox.benchmark.interval}). Nên cửa sổ
 * liveness phải là hai chu kỳ — 30 phút — và con số này <b>không</b> phát hiện được một worker
 * vừa chết ba phút trước.
 *
 * <p>Đó là giới hạn thật, và cách chữa đúng không phải là bịa một con số mịn hơn mà là nhìn
 * đúng chỗ: trong contest, chỉ số nói lên "máy chấm có đang làm việc không" là
 * {@code dangCham} — nó thời gian thực, vì nó đếm lease đang giữ. Dashboard hiện cả hai, và
 * {@code JudgeHostHealthIndicator} cũng vậy.
 *
 * <p>Một heartbeat riêng sẽ mịn hơn, nhưng nó phải là một lượt {@code UPDATE} trên cùng một
 * dòng {@code judge_hosts} từ cả sáu slot — và {@code JdbcJudgeRunRepository} đã ghi rõ từ M1
 * vì sao không làm thế trên đường verdict: sáu slot tuần tự hoá trên một dòng.
 */
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
