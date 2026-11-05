package dev.oj.platform.audit.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

@Component
public class AuditPartitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionScheduler.class);

    private static final int SO_THANG_TRUOC = 3;

    private static final String TAO = "SELECT create_audit_log_partition(CAST(:thang AS date))";

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditPartitionScheduler(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void baoDamPartition() {
        LocalDate thang = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
        for (int i = 0; i <= SO_THANG_TRUOC; i++) {
            LocalDate m = thang.plusMonths(i);
            try {
                jdbc.sql(TAO).param("thang", m).update();
            } catch (RuntimeException e) {
                log.error("Không tạo được partition audit_log cho tháng {} — nếu tình trạng "
                        + "này kéo dài, dòng audit sẽ dồn vào audit_log_default và không gắn "
                        + "lại được nữa.", m, e);
                return;
            }
        }
        log.debug("Partition audit_log đã sẵn tới {}", thang.plusMonths(SO_THANG_TRUOC));
    }
}
