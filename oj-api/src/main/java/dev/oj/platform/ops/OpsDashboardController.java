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
