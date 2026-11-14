package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionSseIT extends PostgresIT {

    @LocalServerPort int port;

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeProgressUseCase recordProgress;
    @Autowired RecordJudgeResultUseCase recordResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;

    @Test
    @DisplayName("★ verdict đi từ /internal/judge/result qua Redis tới trình duyệt")
    void verdict_toi_duoc_trinh_duyet_qua_redis() throws Exception {
        long id = submit();
        try (LuongSse stream = open(id)) {
            stream.awaitLineContaining("QUEUED");

            int attempt = claim(id);
            recordProgress.record(new JudgeProgressDto(id, attempt, 1, 2, 3,
                    List.of(new JudgeProgressDto.TestOutcome(1, Verdict.AC, 12, 2048))));
            stream.awaitLineContaining("JUDGING");

            recordResult.record(accepted(id, attempt));
            String terminal = stream.awaitLineContaining("DONE");

            assertThat(terminal).contains("\"verdict\":\"AC\"", "\"terminal\":true");
        }
    }

    @Test
    @DisplayName("★ SEC3 — không byte nào trên luồng nói test nào sai")
    void luong_khong_mang_chi_tiet_test() throws Exception {
        long id = submit();
        try (LuongSse stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int attempt = claim(id);

            recordResult.record(new JudgeResultDto(id, attempt, Verdict.WA, 0, 100,
                    2, 2, 99, 4096, "log giả", "SG signal=11",
                    "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));
            stream.awaitLineContaining("DONE");

            assertThat(stream.everything())
                    .as("chi tiết đi qua GET /submissions/{id}, nơi có FeedbackPolicy")
                    .doesNotContain("failedTestOrdinal", "compileLog", "isolate");
        }
    }

    @Test
    @DisplayName("bài đã chấm xong thì luồng gửi một sự kiện rồi đóng ngay")
    void bai_da_xong_thi_dong_ngay() throws Exception {
        long id = submit();
        recordResult.record(accepted(id, claim(id)));

        try (LuongSse stream = open(id)) {
            assertThat(stream.awaitLineContaining("DONE")).contains("\"terminal\":true");
            stream.awaitClosed();
        }
    }

    @Test
    @DisplayName("★ B11 · tiến độ của lượt đã bị thu hồi không tới trình duyệt — trước và sau khi có lượt mới")
    void tien_do_cua_luot_cu_khong_toi_trinh_duyet() throws Exception {
        long id = submit();
        try (LuongSse stream = open(id)) {
            stream.awaitLineContaining("QUEUED");
            int cu = claim(id);
            recordProgress.record(tienDo(id, cu, 21));
            stream.awaitLineContaining("\"testsDone\":21");

            jdbc.sql("""
                    UPDATE judge_queue SET lease_until = now() - interval '1 second'
                     WHERE submission_id = :id
                    """).param("id", id).update();
            assertThat(reapStaleJobs.reap()).isEqualTo(1);

            recordProgress.record(tienDo(id, cu, 41));
            int moi = claim(id);
            recordProgress.record(tienDo(id, cu, 61));
            recordProgress.record(tienDo(id, moi, 7));
            stream.awaitLineContaining("\"testsDone\":7");

            assertThat(stream.everything())
                    .as("worker đã mất lease vẫn gửi tiến độ — thanh tiến độ không được nhảy theo nó")
                    .doesNotContain("\"testsDone\":41", "\"testsDone\":61");
        }
    }

    @Test
    @DisplayName("★ bài quay về hàng đợi (reaper thu hồi, IE được chấm lại) thì trình duyệt nhận QUEUED ngay, không đứng ở con số tiến độ cũ")
    void quay_ve_hang_doi_thi_trinh_duyet_nhan_queued() throws Exception {
        long id = submit();
        try (LuongSse stream = open(id)) {
            stream.awaitSoDong(QUEUED, 1);
            int dau = claim(id);
            recordProgress.record(tienDo(id, dau, 21));
            stream.awaitLineContaining("\"testsDone\":21");

            jdbc.sql("""
                    UPDATE judge_queue SET lease_until = now() - interval '1 second'
                     WHERE submission_id = :id
                    """).param("id", id).update();
            assertThat(reapStaleJobs.reap()).isEqualTo(1);
            assertThat(stream.awaitSoDong(QUEUED, 2)).contains("\"attempt\":" + dau);

            int hai = claim(id);
            recordResult.record(new JudgeResultDto(id, hai, Verdict.IE, 0, 100, null, 0, null,
                    null, null, "sandbox hỏng", "mac-m1max-host", new BigDecimal("1.000"),
                    Instant.now(), List.of()));
            assertThat(stream.awaitSoDong(QUEUED, 3)).contains("\"attempt\":" + hai);
        }
    }

    private static final String QUEUED = "\"status\":\"QUEUED\"";

    private static JudgeProgressDto tienDo(long id, int attempt, int toOrdinal) {
        return new JudgeProgressDto(id, attempt, toOrdinal, toOrdinal, 100,
                List.of(new JudgeProgressDto.TestOutcome(toOrdinal, Verdict.AC, 12, 2048)));
    }

    private long submit() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
    }

    private int claim(long expectedId) {
        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow(() -> new AssertionError("hàng đợi rỗng"));
        assertThat(job.submissionId()).isEqualTo(expectedId);
        return job.attempt();
    }

    private static JudgeResultDto accepted(long id, int attempt) {
        return new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 234, 4096,
                null, "OK", "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    @Test
    @DisplayName("★ lỗi trên đường SSE trả JSON có mã, không phải 500 rỗng")
    void loi_tren_duong_sse_van_la_json() throws Exception {
        var client = HttpClient.newHttpClient();
        var url = URI.create("http://localhost:" + port + "/api/v1/submissions/999999/stream");

        for (String[] ca : new String[][]{
                {"không token", null, "401"},
                {"bài không tồn tại", bearerDev(), "404"}}) {
            var req = HttpRequest.newBuilder(url).header("Accept", "text/event-stream");
            if (ca[1] != null) {
                req.header("Authorization", ca[1]);
            }
            var res = client.send(req.GET().build(), HttpResponse.BodyHandlers.ofString());

            assertThat(res.statusCode())
                    .describedAs("ca '%s'", ca[0]).isEqualTo(Integer.parseInt(ca[2]));
            assertThat(res.body())
                    .describedAs("ca '%s' — thân rỗng thì frontend không phân biệt được lỗi gì", ca[0])
                    .contains("\"code\"");
        }
    }

    @Test
    @DisplayName("★ D5 · mở luồng của bài người khác → 404 y hệt bài không tồn tại, không lộ trạng thái")
    void luong_cua_bai_nguoi_khac_la_404() throws Exception {
        long cuaDev = submit();
        String nguoiKhac = bearer(SETTER_ID, "setter", Role.SETTER);

        var cuaNguoiKhac = moThang(cuaDev, nguoiKhac);
        var khongTonTai = moThang(999_999, nguoiKhac);

        assertThat(cuaNguoiKhac.statusCode()).isEqualTo(404);
        assertThat(cuaNguoiKhac.body())
                .describedAs("khác 404 của bài không tồn tại là dò được ai đã nộp bài id này")
                .contains(maLoi(khongTonTai.body()))
                .doesNotContain("QUEUED", "JUDGING", "verdict");
    }

    private HttpResponse<String> moThang(long submissionId, String authorization)
            throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/submissions/" + submissionId + "/stream"))
                .header("Accept", "text/event-stream")
                .header("Authorization", authorization)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String maLoi(String than) {
        var m = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*\"[^\"]+\"").matcher(than);
        assertThat(m.find()).as("thân lỗi không có mã: %s", than).isTrue();
        return m.group();
    }

    private LuongSse open(long submissionId) throws Exception {
        return new LuongSse(URI.create(
                "http://localhost:" + port + "/api/v1/submissions/" + submissionId + "/stream"),
                bearerDev());
    }
}
