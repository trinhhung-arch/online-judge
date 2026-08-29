package dev.oj.judging.application.usecase;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.judging.application.port.JudgeHostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Bước 2.9 — {@code POST /internal/judge/benchmark}. */
class RecordHostBenchmarkUseCaseTest {

    private final FakeHosts hosts = new FakeHosts();
    private final RecordHostBenchmarkUseCase useCase = new RecordHostBenchmarkUseCase(hosts);

    @Test
    @DisplayName("phép đo được ghi lại nguyên vẹn")
    void ghi_lai_phep_do() {
        useCase.record(benchmark(new BigDecimal("1.250"), new BigDecimal("2.00")));

        assertThat(hosts.recorded).hasSize(1);
        assertThat(hosts.recorded.getFirst().hostFactor()).isEqualByComparingTo("1.250");
    }

    /**
     * ★ Máy chấm chưa đăng ký <b>không</b> làm hỏng request.
     *
     * <p>Một worker mới chấm bài được ngay mà không cần ai sửa config phía API (S2,
     * {@code judge_runs.host_id} cho phép NULL). Nếu endpoint này ném lỗi cho máy lạ thì
     * "bật thêm một worker là nó tự vào việc" không còn đúng nữa.
     */
    @Test
    @DisplayName("máy chưa có trong judge_hosts → log rồi thôi, KHÔNG ném")
    void may_la_thi_khong_nem() {
        hosts.known = false;

        assertThatCode(() -> useCase.record(benchmark(BigDecimal.ONE, null)))
                .doesNotThrowAnyException();
    }

    /**
     * Ngưỡng 8% nằm trong hợp đồng để hai phía không tự đặt hai con số. Đây là bẫy throttle
     * nhiệt ({@code nfrplan} rủi ro #5), không phải chuyện hiệu chuẩn.
     */
    @Test
    @DisplayName("drift vượt 8% là đáng báo động, dưới thì không")
    void nguong_drift_nam_trong_hop_dong() {
        assertThat(benchmark(BigDecimal.ONE, new BigDecimal("12.50")).driftIsAlarming()).isTrue();
        assertThat(benchmark(BigDecimal.ONE, new BigDecimal("-9.10")).driftIsAlarming()).isTrue();
        assertThat(benchmark(BigDecimal.ONE, new BigDecimal("7.99")).driftIsAlarming()).isFalse();
        assertThat(benchmark(BigDecimal.ONE, null).driftIsAlarming())
                .as("lần đo đầu tiên không có gì để so")
                .isFalse();
    }

    @Test
    @DisplayName("chưa hiệu chuẩn thì note nói rõ, để bảng lịch sử không trông như đã hiệu chuẩn")
    void note_noi_ro_chua_hieu_chuan() {
        assertThat(new HostBenchmarkDto("may", "arm64", Instant.now(), 630,
                BigDecimal.ONE, false, null).note())
                .contains("630ms", "arm64", "CHƯA hiệu chuẩn");
        assertThat(new HostBenchmarkDto("may", "arm64", Instant.now(), 630,
                BigDecimal.ONE, true, null).note())
                .doesNotContain("CHƯA");
    }

    private static HostBenchmarkDto benchmark(BigDecimal factor, BigDecimal driftPct) {
        return new HostBenchmarkDto("mac-m1max-host", "arm64", Instant.now(), 630,
                factor, true, driftPct);
    }

    private static final class FakeHosts implements JudgeHostRepository {
        final List<HostBenchmarkDto> recorded = new ArrayList<>();
        boolean known = true;

        @Override
        public boolean recordBenchmark(HostBenchmarkDto benchmark) {
            recorded.add(benchmark);
            return known;
        }
    }
}
