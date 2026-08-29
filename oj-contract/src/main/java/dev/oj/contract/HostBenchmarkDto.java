package dev.oj.contract;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Một phép đo tốc độ máy chấm, gửi tới {@code POST /internal/judge/benchmark}.
 *
 * <h2>Vì sao phép đo phải đi qua API chứ không tự ghi vào bảng</h2>
 * Worker không có {@code DataSource} và sẽ không bao giờ có (bất biến #3). Bảng
 * {@code host_benchmarks} tồn tại từ {@code V1}, nhưng trước record này thì không có đường nào
 * để một phép đo tới được nó — lịch sử đo chỉ nằm trong log của worker và <b>mất khi worker
 * khởi động lại</b>.
 *
 * <h2>Vì sao lịch sử ấy đáng một endpoint riêng</h2>
 * Sau một kỳ thi, câu hỏi thường gặp nhất là <i>"bài của em bị TLE, có phải máy chấm hôm đó
 * chậm không?"</i>. Không có bảng này thì câu trả lời là "không biết", và "không biết" trong
 * một hệ thống bán sự công bằng là câu trả lời tệ nhất có thể.
 *
 * <p>Nó <b>không</b> nằm trên đường {@code nộp bài → verdict}: worker gọi từ luồng lịch riêng,
 * 15 phút một lần, nên nó không tiêu một phần nào của ngân sách 2 giây ({@code nfrplan.md} 2.1).
 *
 * @param hostName   khớp {@code judge_hosts.name}. Worker gửi <i>tên</i> và không biết
 *                   {@code judge_hosts.id} tồn tại — việc tra id là của API
 * @param arch       {@code arm64} hoặc {@code amd64}. Một con số thời gian không kèm kiến trúc
 *                   là một con số vô nghĩa ({@code nfrplan.md} 9.1)
 * @param measuredAt thời điểm đo, do worker ghi. Dùng giờ của worker chứ không phải
 *                   {@code now()} của Postgres vì phép đo xảy ra ở worker, và một phép đo bị
 *                   giữ trong buffer 30 giây rồi mới gửi được thì mốc thời gian phải là lúc đo
 * @param cpuTimeMs  thời gian CPU của tải chuẩn, trung vị nhiều lần chạy. <b>Đây mới là con số
 *                   thô so sánh được giữa hai lần đo</b>; {@code hostFactor} là số dẫn xuất và
 *                   chỉ có nghĩa sau khi máy chấm chuẩn được hiệu chuẩn
 * @param hostFactor hệ số đang dùng để nhân vào giới hạn thời gian
 * @param calibrated {@code false} nghĩa là chưa ai đo tải chuẩn trên máy chấm chuẩn, nên
 *                   {@code hostFactor} là hằng số tĩnh trong cấu hình chứ không phải số đo.
 *                   Không có cờ này thì bảng lịch sử đầy những {@code host_factor = 1.000}
 *                   trông như đã hiệu chuẩn
 * @param driftPct   lệch bao nhiêu phần trăm so với <b>lần đo đầu tiên của chính máy này</b>,
 *                   hoặc {@code null} nếu đây là lần đầu. Vượt ngưỡng là dấu hiệu throttle
 *                   nhiệt ({@code nfrplan.md} Phần 13, rủi ro #5) — không phải chuyện hiệu chuẩn
 */
public record HostBenchmarkDto(
        String hostName,
        String arch,
        Instant measuredAt,
        long cpuTimeMs,
        BigDecimal hostFactor,
        boolean calibrated,
        BigDecimal driftPct) {

    /** Ngưỡng công bố ở {@code nfrplan.md} 9.1 và {@code oj.worker.sandbox.benchmark}. */
    public static final double DRIFT_ALERT_PCT = 8.0;

    public HostBenchmarkDto {
        ContractChecks.requireText(hostName, "hostName");
        ContractChecks.requireText(arch, "arch");
        if (!"arm64".equals(arch) && !"amd64".equals(arch)) {
            throw new IllegalArgumentException(
                    "arch phải là arm64 hoặc amd64 (khớp CHECK trên judge_hosts): " + arch);
        }
        if (measuredAt == null) {
            throw new NullPointerException("measuredAt");
        }
        ContractChecks.requirePositive(cpuTimeMs, "cpuTimeMs");
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương, nhận: " + hostFactor);
        }
    }

    /** Có nên đánh động người vận hành không — cùng một ngưỡng ở cả hai phía. */
    public boolean driftIsAlarming() {
        return driftPct != null && driftPct.abs().doubleValue() > DRIFT_ALERT_PCT;
    }

    /** Ghi vào {@code host_benchmarks.note}: con số thô, để so được giữa hai lần đo. */
    public String note() {
        return "tải chuẩn " + cpuTimeMs + "ms CPU trên " + arch
                + (calibrated ? "" : " (CHƯA hiệu chuẩn — host_factor là hằng số tĩnh)");
    }
}
