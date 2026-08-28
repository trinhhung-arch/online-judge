package dev.oj.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Mọi con số của worker — một chỗ duy nhất, không rải trong code.
 *
 * <p><b>Không có thuộc tính nào liên quan tới database, và sẽ không bao giờ có</b> (bất biến
 * #3). Worker biết đúng một địa chỉ HTTP và một secret.
 *
 * @param hostName   khớp {@code judge_hosts.name}. API tra ra id; worker không biết id tồn tại
 * @param arch       {@code arm64} hoặc {@code amd64} — một con số thời gian không kèm kiến
 *                   trúc là một con số vô nghĩa ({@code nfrplan.md} 9.1)
 * @param slots      số box chấm song song. <b>Cố định theo cấu hình, KHÔNG theo số core</b>:
 *                   M1 Max có 10 core nhưng chạy 6 slot, vì chạy full core 10-15 phút sẽ
 *                   throttle và bài phút thứ 90 chấm chậm hơn bài phút thứ 5 — mất công bằng
 *                   ngay giữa contest (ADR 008)
 * @param lease      bản sao của {@code oj.judge.lease} phía API, dùng để <b>cảnh báo</b> khi
 *                   một lượt chấm sắp vượt hạn. Vượt rồi thì kết quả sẽ bị khoá lạc quan từ
 *                   chối, nên chấm tiếp là phí một slot
 * @param hostFactor hệ số hiệu chuẩn. M1 để 1.000; M2 (Bước 2.9) đo bằng {@code HostBenchmark}
 */
@ConfigurationProperties(prefix = "oj.worker")
public record WorkerProperties(
        String hostName,
        String arch,
        int slots,
        Duration lease,
        Duration idlePoll,
        Duration requestTimeout,
        Duration resultRetryMin,
        Duration resultRetryMax,
        String apiBaseUrl,
        String internalSecret,
        java.math.BigDecimal hostFactor) {

    public WorkerProperties {
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalStateException("oj.worker.host-name bắt buộc — API dùng nó để tra "
                    + "judge_hosts và để ghi vào judge_runs");
        }
        if (slots < 1 || slots > 32) {
            throw new IllegalStateException(
                    "oj.worker.slots ngoài [1..32]: " + slots + ". Khớp CHECK trên judge_hosts, "
                            + "và đọc ADR 008 trước khi tăng con số này");
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException("oj.worker.api-base-url bắt buộc");
        }
        // Cùng tinh thần với AppProperties.Internal phía API: thà không khởi động được còn hơn
        // chạy rồi nhận 401 cho mọi request mà không hiểu vì sao.
        if (internalSecret == null || internalSecret.length() < 32) {
            throw new IllegalStateException(
                    "Thiếu OJ_INTERNAL_SHARED_SECRET (cần >= 32 ký tự). Đây là thứ duy nhất "
                            + "cho worker quyền ghi verdict — không có giá trị mặc định, cố ý");
        }
        if (idlePoll == null || idlePoll.isZero() || idlePoll.isNegative()) {
            throw new IllegalStateException("oj.worker.idle-poll phải dương");
        }
    }
}
