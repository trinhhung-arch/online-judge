package dev.oj.platform.ops;

import dev.oj.platform.metrics.OjMetrics;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.search.Search;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Map;

/**
 * FR-ADM-04 — dashboard vận hành. {@code GET /api/v1/admin/ops}. Bước 6.10.
 *
 * <h2>★ Lớp này KHÔNG import module nghiệp vụ nào, và đó là cả thiết kế</h2>
 * Dashboard cần số liệu của {@code judging} (hàng đợi, tỉ lệ IE), của {@code contests} (drift
 * bảng xếp hạng), và sẽ cần của {@code ai} (chi phí LLM). Ba module ở ba tầng khác nhau của
 * chiều phụ thuộc, nên <b>không có chỗ nào trong đồ thị module đặt được một lớp import cả
 * ba</b> — luật ArchUnit 3 chặn mọi phương án.
 *
 * <p>Đường đi: mỗi module tự <i>đăng ký gauge của mình</i> vào {@link MeterRegistry}, và lớp
 * này chỉ <i>đọc registry</i>. {@code MeterRegistry} là hạ tầng của {@code platform}, ai cũng
 * ghi vào được, và không ai phải biết ai. Tên chỉ số nằm ở {@link OjMetrics} để hai đầu không
 * lệch nhau.
 *
 * <p>Hệ quả đáng chú ý: thêm một ô mới lên dashboard là <b>đăng ký một gauge</b>, không phải
 * sửa file này. {@code ai} ở tuần 14–15 sẽ thêm chi phí LLM (AI2) mà không chạm một dòng nào
 * ở đây.
 *
 * <h2>Vì sao không dùng thẳng {@code /actuator/metrics}</h2>
 * Ba lý do: nó nằm ở cổng 8081 chỉ nghe nội bộ (không qua tunnel, nên trang admin không gọi
 * được); nó không có phân quyền theo vai trò của hệ thống này; và nó trả về hình dạng của
 * Micrometer chứ không phải câu trả lời cho câu hỏi người trực đang có. Sáu con số dưới đây
 * là sáu con số của FR-ADM-04, không hơn.
 */
@RequiresRole(Role.ADMIN)
@RestController
@RequestMapping("/api/v1/admin/ops")
public class OpsDashboardController {

    private final MeterRegistry metrics;

    public OpsDashboardController(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @GetMapping
    public Map<String, Object> doc() {
        Map<String, Object> ket = new LinkedHashMap<>();
        ket.put("hangDoiDangCho", (long) gauge(OjMetrics.QUEUE_WAITING));
        ket.put("hangDoiDangCham", (long) gauge(OjMetrics.QUEUE_JUDGING));
        ket.put("rejudgeDangCho", (long) gauge(OjMetrics.QUEUE_REJUDGE_WAITING));
        ket.put("choLauNhatMs", (long) gauge(OjMetrics.QUEUE_WAIT_MS));
        ket.put("mayChamSong", (long) gauge(OjMetrics.WORKERS_LIVE));
        ket.put("baiDaCham", (long) tongVerdict(null));
        ket.put("tiLeIe", tiLeIe());
        ket.put("drift", (long) gauge(OjMetrics.STANDINGS_DRIFT));
        ket.put("apiP95Ms", p95ApiMs());
        return ket;
    }

    private double gauge(String ten) {
        Gauge g = metrics.find(ten).gauge();
        return g == null ? 0 : g.value();
    }

    /**
     * R3 — tỉ lệ IE, tính từ <b>một</b> counter tách theo tag.
     *
     * <p>Trả tỉ lệ chứ không trả hai số đếm: ngưỡng alert của R3 là {@code > 0.5%}, và một
     * người trực lúc 2 giờ sáng không nên phải tự chia. Mẫu số 0 (chưa chấm bài nào) trả 0
     * thay vì {@code NaN} — {@code NaN} trên dashboard đọc như một sự cố.
     */
    private double tiLeIe() {
        double tong = tongVerdict(null);
        return tong == 0 ? 0 : tongVerdict("IE") / tong;
    }

    private double tongVerdict(String verdict) {
        Search tim = metrics.find(OjMetrics.JUDGE_FINISHED);
        if (verdict != null) {
            tim = tim.tag(OjMetrics.TAG_VERDICT, verdict);
        }
        double tong = 0;
        for (Counter c : tim.counters()) {
            tong += c.count();
        }
        return tong;
    }

    /**
     * P1 và P2 gộp thành một con số: p95 của <b>mọi</b> request.
     *
     * <p>Tách theo endpoint là việc của Micrometer và của người đi tìm nguyên nhân; ô trên
     * dashboard chỉ cần trả lời "API có đang chậm không". Ngưỡng chặt hơn trong hai ngưỡng
     * (P1: 200ms) là ngưỡng để nhìn.
     */
    private double p95ApiMs() {
        double xauNhat = 0;
        for (Timer t : metrics.find("http.server.requests").timers()) {
            for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
                if (v.percentile() == 0.95) {
                    xauNhat = Math.max(xauNhat, v.value(TimeUnit.MILLISECONDS));
                }
            }
        }
        return Math.round(xauNhat * 10) / 10.0;
    }
}
