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

/**
 * Tạo trước partition của {@code audit_log} — Bước 6.5.
 *
 * <h2>Vì sao việc này không được phép quên, và vì sao nó khó nhớ</h2>
 * {@code audit_log} phân mảnh theo {@code occurred_at}. Ngày 1 tháng sau, một dòng audit
 * không thuộc partition nào sẽ rơi vào {@code audit_log_default} — V5 tạo sẵn partition ấy
 * đúng để <b>không mất một dòng nào</b>. Nhưng nó không miễn phí: khi DEFAULT đã chứa dòng
 * thuộc khoảng của tháng đó, Postgres <b>từ chối</b> gắn partition thật cho tháng ấy
 * ({@code ATTACH} phải quét DEFAULT và thấy dòng xung đột). Từ đó trở đi mọi dòng của mọi
 * tháng dồn hết vào một bảng, và partition pruning — lý do duy nhất bảng này được phân mảnh —
 * biến mất.
 *
 * <p>Triệu chứng xuất hiện nhiều tháng sau, dưới dạng "trang audit chậm dần". Đó là loại lỗi
 * mà một dòng lịch chạy hằng ngày rẻ hơn nhiều so với việc gỡ.
 *
 * <h2>Hằng ngày, tạo dư ba tháng — không phải hằng tháng, tạo một tháng</h2>
 * Chạy hằng tháng nghĩa là một lần API tắt đúng ngày đó là lỡ cả tháng. Chạy hằng ngày và
 * tạo dư ba tháng thì phải tắt liên tục ba tháng mới lỡ, và hàm
 * {@code create_audit_log_partition} tự bỏ qua tháng đã có nên chạy thừa không tốn gì.
 *
 * <h2>{@code SECURITY DEFINER} — xem V8</h2>
 * Sau V8, role {@code oj_app} không còn quyền DDL. Hàm này được khai báo
 * {@code SECURITY DEFINER} đúng để lời gọi dưới đây vẫn tạo được bảng. Bỏ V8 đi thì đoạn code
 * này vẫn chạy; bỏ {@code SECURITY DEFINER} đi thì nó hỏng vào một ngày cuối tháng.
 */
@Component
public class AuditPartitionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionScheduler.class);

    /** Bao nhiêu tháng tới được tạo sẵn. Xem javadoc lớp về việc vì sao dư. */
    private static final int SO_THANG_TRUOC = 3;

    private static final String TAO = "SELECT create_audit_log_partition(CAST(:thang AS date))";

    private final JdbcClient jdbc;
    private final Clock clock;

    public AuditPartitionScheduler(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Nuốt mọi ngoại lệ ở tầng ngoài cùng — cùng lý do với {@code StaleJobReaper} và
     * {@code JobRunner}: Spring <b>huỷ hẳn</b> một tác vụ {@code @Scheduled} ném ra ngoài, và
     * một lỗi tạm thời sẽ làm việc này chết im lặng cho tới lần deploy sau.
     */
    @Scheduled(cron = "0 15 3 * * *")     // 03:15 mỗi ngày — giờ ít người dùng nhất
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
