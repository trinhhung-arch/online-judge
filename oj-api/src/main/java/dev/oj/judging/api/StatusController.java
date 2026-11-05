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

    public record TrangThaiCongKhai(boolean dangNhanBai, int dangCho, int dangCham,
                                    int mayChamSong, long choLauNhatMs, long choUocTinhMs) {
    }
}
