package dev.oj.judging.api;

import dev.oj.judging.application.published.QueueStatusQuery;
import dev.oj.judging.infrastructure.QueueMetricsSampler;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.PublicAccess;
import dev.oj.platform.settings.SystemSettings;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

/**
 * FR-ADM-05 — trang trạng thái <b>công khai</b>. {@code GET /api/v1/status}. Bước 6.11.
 *
 * <h2>Vì sao công khai, và vì sao nó chỉ có bốn con số</h2>
 * Người dùng nộp bài rồi chờ. Không có trang này, "chậm" và "hỏng" trông giống hệt nhau, và
 * cách duy nhất để biết là bấm F5 — tức là đúng lúc hệ thống chậm thì nó nhận thêm tải
 * ({@code nfrplan.md} 6.1: U1/U2, và Phần 7 degraded mode).
 *
 * <p>Nhưng công khai nghĩa là <b>ai cũng đọc được, kể cả trong lúc contest</b>. Nên bốn con số
 * ở đây được chọn kỹ: chúng nói về <i>máy chấm</i>, không về <i>bài nộp</i>. Không có
 * {@code submissionId}, không có tên đề, không có ai đang nộp bài nào — biết "có 40 bài đang
 * chờ" không cho ai lợi thế nào trong kỳ thi, còn biết "40 bài của đề C" thì có.
 *
 * <h2>Không chạm database</h2>
 * Đọc mẫu gần nhất của {@link QueueMetricsSampler}. Đây là trang có lưu lượng cao nhất đúng
 * vào lúc database tải nặng nhất; xem javadoc của lớp đó.
 */
@PublicAccess("FR-ADM-05: trang trạng thái phải đọc được khi CHƯA đăng nhập — người dùng cần "
        + "biết hệ thống còn sống trước khi họ đăng nhập được. Bốn con số ở đây nói về máy "
        + "chấm, không về bài nộp của ai.")
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final QueueMetricsSampler mau;
    private final SystemSettings congTac;
    private final AppProperties properties;
    private final Clock clock;

    public StatusController(QueueMetricsSampler mau, SystemSettings congTac,
                            AppProperties properties, Clock clock) {
        this.mau = mau;
        this.congTac = congTac;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping
    public TrangThaiCongKhai doc() {
        QueueStatusQuery.TrangThai t = mau.mauGanNhat();
        return new TrangThaiCongKhai(
                congTac.bat(SystemSettings.NHAN_BAI_NOP, true),
                t.dangCho(),
                t.dangCham(),
                t.mayChamSong(),
                t.choLauNhatMs(clock.instant()),
                t.choUocTinhMs(properties.judge().throughputEstimate()));
    }

    /**
     * @param dangNhanBai      FR-ADM-06 — {@code false} là đang bảo trì. Để UI hiện lý do
     *                         <i>trước</i> khi người dùng gõ xong bài và bấm nộp, thay vì một
     *                         thông báo 503 sau đó
     * @param choLauNhatMs     ĐO ĐƯỢC: bài chờ lâu nhất hiện đã chờ bao lâu
     * @param choUocTinhMs     ƯỚC TÍNH: hàng đợi chia cho throughput đã cam kết. Hai trường
     *                         tách nhau vì chúng có độ tin cậy khác nhau, và gộp chúng thành
     *                         một con số "thời gian chờ" là làm mất thông tin đó —
     *                         xem {@code QueueStatusQuery.TrangThai.choUocTinhMs}
     */
    public record TrangThaiCongKhai(boolean dangNhanBai, int dangCho, int dangCham,
                                    int mayChamSong, long choLauNhatMs, long choUocTinhMs) {
    }
}
