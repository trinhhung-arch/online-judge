package dev.oj.judging.infrastructure;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.judging.application.port.JudgeHostRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JdbcJudgeHostRepository implements JudgeHostRepository {

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
