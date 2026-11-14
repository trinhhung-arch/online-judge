package dev.oj.worker.report;

import dev.oj.contract.CheckerType;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.WorkerFixtures;
import dev.oj.worker.client.JudgeApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchReporterTest {

    private static final Duration KHOANG = Duration.ofSeconds(30);

    private final List<JudgeProgressDto> daGui = new ArrayList<>();
    private Instant bayGio = Instant.parse("2026-11-14T00:00:00Z");

    private final BatchReporter reporter = new BatchReporter(
            new JudgeApiClient(WorkerFixtures.properties(Path.of("/tmp/oj-test-cache")),
                    RestClient.builder()) {
                @Override
                public void reportProgress(JudgeProgressDto progress) {
                    daGui.add(progress);
                }
            }, KHOANG, () -> bayGio);

    @Test
    @DisplayName("đủ 20 test thì gửi một lô, chưa đủ và chưa tới hạn thì chưa gửi")
    void gui_theo_lo_20() {
        try (BatchReporter.Session phien = reporter.open(job(100))) {
            for (int i = 1; i <= 19; i++) {
                phien.add(i, Verdict.AC, 5, 1024);
            }
            assertThat(daGui).isEmpty();

            phien.add(20, Verdict.AC, 5, 1024);
            assertThat(daGui).singleElement().satisfies(lo -> {
                assertThat(lo.fromOrdinal()).isEqualTo(1);
                assertThat(lo.toOrdinal()).isEqualTo(20);
                assertThat(lo.outcomes()).hasSize(20);
            });
        }
    }

    @Test
    @DisplayName("★ ADR 026 · test chạy lâu: quá progress-interval thì gửi ngay ở test vừa xong, không chờ đủ 20")
    void qua_han_thi_gui_du_chua_du_lo() {
        try (BatchReporter.Session phien = reporter.open(job(100))) {
            troi(KHOANG.minusMillis(1));
            phien.add(1, Verdict.AC, 5, 1024);
            assertThat(daGui).as("thiếu 1ms thì chưa gửi").isEmpty();

            troi(Duration.ofMillis(1));
            phien.add(2, Verdict.AC, 5, 1024);
            assertThat(daGui).singleElement().satisfies(lo -> {
                assertThat(lo.fromOrdinal()).isEqualTo(1);
                assertThat(lo.toOrdinal()).isEqualTo(2);
            });

            troi(KHOANG.minusMillis(1));
            phien.add(3, Verdict.AC, 5, 1024);
            assertThat(daGui).as("đồng hồ tính lại từ lần gửi trước").hasSize(1);

            troi(Duration.ofMillis(1));
            phien.add(4, Verdict.AC, 5, 1024);
            assertThat(daGui).hasSize(2);
            assertThat(daGui.get(1).fromOrdinal()).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("đóng phiên gửi phần còn lại, và không bao giờ gửi một lô rỗng")
    void dong_phien_gui_phan_con_lai() {
        try (BatchReporter.Session phien = reporter.open(job(3))) {
            phien.add(1, Verdict.AC, 5, 1024);
            phien.add(2, Verdict.WA, 5, 1024);
        }
        assertThat(daGui).singleElement()
                .satisfies(lo -> assertThat(lo.toOrdinal()).isEqualTo(2));

        reporter.open(job(3)).close();
        assertThat(daGui).hasSize(1);
    }

    private void troi(Duration d) {
        bayGio = bayGio.plus(d);
    }

    private static JudgeJobDto job(int soTest) {
        List<TestcaseMetaDto> tests = new ArrayList<>();
        for (int i = 1; i <= soTest; i++) {
            tests.add(new TestcaseMetaDto(i, false, "a".repeat(64), "b".repeat(64), null));
        }
        return JudgeJobDto.builder()
                .submission(101, 1)
                .traceId("trace-test")
                .language("cpp20", "g++ -o {bin} {src}", "{bin}")
                .compileLimits(20_000, 524_288)
                .runLimitsOnReferenceHost(1_000, 65_536, 65_536)
                .source("Main.cpp", "int main(){}", "c".repeat(64))
                .checker(CheckerType.TOKEN, null)
                .scoring(ScoringMode.ALL_OR_NOTHING, 100)
                .testdata(1, "d".repeat(64), tests)
                .build();
    }
}
