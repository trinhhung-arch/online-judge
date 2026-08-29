package dev.oj.judging.infrastructure;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.judging.application.port.JudgeHostRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Pool {@code judge}: đây là traffic của worker, và nó phải chịu chung trần 6 connection với
 * việc chấm bài chứ không được mượn pool của người dùng. Một máy chấm hỏng làm ngập endpoint
 * này thì cùng lắm là chấm chậm, chứ không được làm cả website không mở nổi trang chủ.
 */
@Repository
public class JdbcJudgeHostRepository implements JudgeHostRepository {

    /**
     * Cập nhật {@code host_factor} và {@code last_seen_at}, trả về id để ghi phép đo.
     *
     * <p>{@code UPDATE ... RETURNING id} thay vì {@code SELECT} rồi {@code UPDATE}: một lượt
     * round-trip thay vì hai, và không có khe hở giữa hai câu.
     *
     * <p>{@code JdbcJudgeRunRepository} cố ý <b>không</b> đụng tới {@code last_seen_at} vì
     * sáu slot của cùng một máy sẽ tranh khoá trên đúng một dòng và mọi verdict từ máy đó bị
     * tuần tự hoá. Ở đây thì an toàn: mỗi worker gọi 15 phút một lần, không phải mỗi bài nộp.
     */
    private static final String TOUCH_HOST = """
            UPDATE judge_hosts
               SET host_factor  = :hostFactor,
                   last_seen_at = :measuredAt
             WHERE name = :hostName
            RETURNING id
            """;

    private static final String INSERT_BENCHMARK = """
            INSERT INTO host_benchmarks (host_id, measured_at, host_factor, drift_pct, note)
            VALUES (:hostId, :measuredAt, :hostFactor, :driftPct, :note)
            """;

    private final JdbcClient jdbc;

    public JdbcJudgeHostRepository(@Qualifier("judgeJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordBenchmark(HostBenchmarkDto benchmark) {
        OffsetDateTime measuredAt =
                OffsetDateTime.ofInstant(benchmark.measuredAt(), ZoneOffset.UTC);

        Optional<Integer> hostId = jdbc.sql(TOUCH_HOST)
                .param("hostFactor", benchmark.hostFactor())
                .param("measuredAt", measuredAt)
                .param("hostName", benchmark.hostName())
                .query(Integer.class)
                .optional();

        if (hostId.isEmpty()) {
            return false;
        }

        jdbc.sql(INSERT_BENCHMARK)
                .param("hostId", hostId.get())
                .param("measuredAt", measuredAt)
                .param("hostFactor", benchmark.hostFactor())
                .param("driftPct", benchmark.driftPct())
                .param("note", benchmark.note())
                .update();
        return true;
    }
}
