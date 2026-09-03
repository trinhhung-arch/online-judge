package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.HostBenchmarkDto;
import dev.oj.contract.JudgeEndpoints;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Vòng nộp bài → verdict đi qua <b>HTTP thật</b>, trên Postgres thật.
 *
 * <p>Mười bảy IT còn lại gọi thẳng use-case trong cùng tiến trình, nên chúng không chứng minh
 * được nửa quan trọng của hợp đồng: <b>JSON có serialize đúng không</b>. Một trường đổi tên,
 * một kiểu thời gian Jackson không đọc được, một record thiếu tham số tên — cả ba đều lọt qua
 * mọi test trước và chỉ hiện ra khi worker thật gọi tới.
 *
 * <p>Đây cũng là chỗ duy nhất kiểm {@code InternalSecretFilter} đứng đúng chỗ trong chuỗi
 * filter, và kiểm rằng {@code /internal/**} <b>không</b> nằm dưới {@code /api/v1/}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalJudgeHttpIT extends PostgresIT {

    /** Kho trong bộ nhớ, cắm làm {@code @Primary} bởi {@link KhoTestdataTrongBoNho.Dang}. */
    @org.springframework.beans.factory.annotation.Autowired
    private KhoTestdataTrongBoNho kho;

    /**
     * Bản sao của {@code HttpIT.THAN_JSON}. Lớp này kế thừa {@link PostgresIT} chứ không phải
     * {@code HttpIT} — nó tự dựng client cho cổng nội bộ, nơi xác thực bằng shared secret chứ
     * không bằng JWT, nên các helper của {@code HttpIT} (đều gắn với {@code Authorization:
     * Bearer}) không dùng được ở đây.
     */
    private static final org.springframework.core.ParameterizedTypeReference<
            java.util.Map<String, Object>> THAN_JSON =
            new org.springframework.core.ParameterizedTypeReference<>() {
            };

    private static final String SECRET = "x".repeat(32);   // khớp PostgresIT

    @LocalServerPort
    int port;

    private RestClient http;

    @BeforeEach
    void restClient() {
        http = RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("★ nộp bài → claim → trả kết quả → xem verdict, tất cả qua HTTP")
    void vong_cham_bai_hoat_dong_qua_HTTP() {
        // ---- 1. Nộp bài: 202, và KHÔNG có verdict trong response ----
        var accepted = http.post().uri("/api/v1/submissions")
                .header("Authorization", bearerDev())
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "// EXPECT: AC\nint main(){return 0;}"))
                .retrieve().toEntity(THAN_JSON);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).containsEntry("status", "QUEUED")
                .doesNotContainKey("verdict");
        long submissionId = ((Number) accepted.getBody().get("submissionId")).longValue();

        // ---- 2. Worker xin việc: JudgeJobDto phải qua được JSON nguyên vẹn ----
        JudgeJobDto job = http.post().uri(JudgeEndpoints.CLAIM)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .retrieve().body(JudgeJobDto.class);

        assertThat(job).isNotNull();
        assertThat(job.submissionId()).isEqualTo(submissionId);
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.sourceContent()).contains("EXPECT: AC");   // quyết định B
        assertThat(job.maxScore()).isEqualTo(100);                // API tính, worker dùng lại
        assertThat(job.testcases()).isNotEmpty();
        assertThat(job.compileCommand()).contains("g++");
        assertThat(job.traceId()).isNotBlank();

        // ---- 3. Trả kết quả: 204 ----
        assertThat(postResult(result(submissionId, 1, Verdict.AC)))
                .isEqualTo(HttpStatus.NO_CONTENT);

        // ---- 4. ★ Gửi lại kết quả KHÁC cho cùng attempt: vẫn 204, verdict KHÔNG đổi ----
        assertThat(postResult(result(submissionId, 1, Verdict.WA)))
                .isEqualTo(HttpStatus.NO_CONTENT);

        // ---- 5. Người dùng đọc verdict ----
        Map<String, Object> detail = http.get().uri("/api/v1/submissions/" + submissionId)
                .header("Authorization", bearerDev())
                .retrieve().body(THAN_JSON);

        assertThat(detail).containsEntry("status", "DONE").containsEntry("verdict", "AC");
        assertThat(detail).containsEntry("timeMs", 230);          // 234 làm tròn 10ms
        assertThat(detail).containsEntry("measurementNote", "đo trên máy chấm chuẩn, sai số ±5%");

        // ★ Bước 3.11 — trường này TỒN TẠI từ M3, và ở đây nó null vì bài AC không có test
        // nào sai. Trước M3 nó vắng mặt hoàn toàn: bộ lọc feedback_level chưa được nối, nên
        // thà thiếu còn hơn lộ. Ca kiểm bộ lọc thật nằm ở SubmissionFeedbackIT.
        assertThat(detail).containsKey("failedTestOrdinal");
        assertThat(detail.get("failedTestOrdinal")).isNull();

        // ★ Bước 3.11 · U3 — verdict phải nói được lý do, không chỉ là hai chữ viết tắt.
        assertThat((String) detail.get("explanation"))
                .isNotBlank()
                .contains("230ms")
                .doesNotContain("/box", "isolate");
    }

    @Test
    @DisplayName("★ /internal không có secret → 401, không phải 200 rỗng")
    void khong_co_secret_thi_khong_vao_duoc() {
        HttpStatusCode status = http.post().uri(JudgeEndpoints.CLAIM)
                .body(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("★ /internal KHÔNG nằm dưới /api/v1 — tunnel chỉ publish /api/v1/**")
    void endpoint_noi_bo_khong_ton_tai_duoi_tien_to_cong_khai() {
        HttpStatusCode status = http.post().uri("/api/v1" + JudgeEndpoints.CLAIM)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // =========================================================================
    // ★★ GET /internal/judge/testdata/{sha256} — đường ra của NỘI DUNG TESTCASE ẨN.
    //
    // Mắt xích này từng thiếu suốt bốn mốc: TestcaseMetaDto chỉ mang hash, javadoc của nó
    // nói worker tải "từ MinIO", còn oj-worker/CLAUDE.md mục 3 cấm worker có MinIO client.
    // Không ai viết đoạn vận chuyển, và không test nào đỏ — vì mọi test của worker tự đổ
    // testdata vào thư mục cục bộ trước khi chạy.
    // =========================================================================

    @Test
    @DisplayName("★★ tải được nội dung theo hash, đúng byte")
    void tai_duoc_testdata_theo_hash() {
        String noiDung = "3 4\n";
        String sha = kho.them(noiDung);

        byte[] ve = http.get().uri(JudgeEndpoints.TESTDATA + "/" + sha)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .retrieve().body(byte[].class);

        assertThat(ve).isNotNull();
        assertThat(new String(ve, java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(noiDung);
    }

    /**
     * ★ Ca quan trọng nhất của cả khối này. Không có secret thì nội dung testcase ẩn ra khỏi
     * hệ thống — và đó là bất biến #1, thứ mà mọi quy tắc khác của dự án phục vụ.
     */
    @Test
    @DisplayName("★★ không có secret → 401, KHÔNG trả một byte nội dung nào")
    void khong_co_secret_thi_khong_tai_duoc_testdata() {
        String sha = kho.them("bi mat khong duoc ra ngoai\n");

        var res = http.get().uri(JudgeEndpoints.TESTDATA + "/" + sha)
                .exchange((req, r) -> java.util.Map.entry(
                        r.getStatusCode(),
                        new String(r.getBody().readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(res.getKey()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getValue())
                .as("401 mà vẫn kèm nội dung thì chốt quyền chỉ là trang trí")
                .doesNotContain("bi mat");
    }

    @Test
    @DisplayName("★ KHÔNG nằm dưới /api/v1 — tunnel không publish được nó")
    void testdata_khong_ton_tai_duoi_tien_to_cong_khai() {
        String sha = kho.them("x\n");

        HttpStatusCode status = http.get().uri("/api/v1" + JudgeEndpoints.TESTDATA + "/" + sha)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Hash sai định dạng và hash không tồn tại phải cho <b>cùng một 404</b>. Phân biệt chúng
     * là xác nhận "chuỗi này đúng định dạng nhưng không có trong kho" — một tín hiệu cho
     * người đang dò, và là thứ duy nhất họ cần để biết mình đoán đúng dạng khoá.
     */
    @Test
    @DisplayName("★ hash không tồn tại và hash dị dạng đều là 404, cùng một câu")
    void hash_la_hoac_khong_co_deu_404() {
        for (String hash : new String[]{
                "a".repeat(64),                       // đúng dạng, không có trong kho
                "KHONG-PHAI-HEX" + "0".repeat(50),    // 64 ký tự nhưng không phải hex
                "abc",                                // quá ngắn
                "../../../etc/passwd"}) {             // đi ngang thư mục
            HttpStatusCode status = http.get().uri(JudgeEndpoints.TESTDATA + "/" + hash)
                    .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                    .exchange((req, res) -> res.getStatusCode());

            assertThat(status).as("hash '%s'", hash).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    /** Hàng đợi rỗng phải là 204, không phải 200 với thân rỗng. */
    @Test
    void hang_doi_rong_tra_204() {
        HttpStatusCode status = http.post().uri(JudgeEndpoints.CLAIM)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** Payload sai phải là 400 — worker nhìn 5xx sẽ retry mãi một thứ không bao giờ hợp lệ. */
    @Test
    void payload_sai_tra_400_chu_khong_phai_500() {
        HttpStatusCode status = http.post().uri(JudgeEndpoints.RESULT)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(Map.of("submissionId", 1, "attempt", 1, "verdict", "KHONG_TON_TAI"))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * ★ Bước 2.9 — phép đo tốc độ máy chấm đi trọn đường: worker → HTTP → Postgres.
     *
     * <p>Worker không có {@code DataSource} (bất biến #3), nên đây là <b>đường duy nhất</b>
     * để lịch sử hiệu chuẩn tới được bảng {@code host_benchmarks}. Trước endpoint này, lịch
     * sử ấy chỉ nằm trong log của worker và mất khi worker khởi động lại — và sau một kỳ thi
     * thì câu "máy chấm hôm đó có chậm không" không có gì để trả lời.
     */
    @Test
    @DisplayName("★ phép đo tốc độ máy vào tới judge_hosts và host_benchmarks")
    void phep_do_toc_do_may_duoc_ghi_lai() {
        HttpStatusCode status = postBenchmark(new HostBenchmarkDto(
                "mac-m1max-host", "arm64", Instant.now(), 630,
                new BigDecimal("1.250"), true, new BigDecimal("11.30")));

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT);

        var row = jdbc.sql("SELECT b.host_factor, b.drift_pct, b.note, "
                        + "h.host_factor AS host_current, h.last_seen_at "
                        + "FROM host_benchmarks b JOIN judge_hosts h ON h.id = b.host_id "
                        + "WHERE h.name = 'mac-m1max-host' "
                        + "ORDER BY b.measured_at DESC LIMIT 1")
                .query().singleRow();

        assertThat((BigDecimal) row.get("host_factor")).isEqualByComparingTo("1.250");
        assertThat((BigDecimal) row.get("drift_pct")).isEqualByComparingTo("11.30");
        assertThat((BigDecimal) row.get("host_current"))
                .as("host_factor của máy phải được cập nhật theo phép đo mới nhất")
                .isEqualByComparingTo("1.250");
        assertThat(row.get("note").toString())
                .as("con số thô mới là thứ so được giữa hai lần đo; host_factor là số dẫn xuất")
                .contains("630ms", "arm64");
        assertThat(row.get("last_seen_at")).isNotNull();
    }

    /**
     * Một máy chấm chưa đăng ký vẫn chấm bài được ({@code judge_runs.host_id} cho phép NULL,
     * S2), nên nó cũng không đáng bị một mã lỗi ở đây — "bật thêm một worker là nó tự vào
     * việc, không sửa một dòng config nào phía API" phải đúng cả với endpoint này.
     */
    @Test
    @DisplayName("máy chấm lạ vẫn nhận 204, không phải 404")
    void may_cham_la_van_nhan_204() {
        assertThat(postBenchmark(new HostBenchmarkDto(
                "may-chua-ai-dang-ky", "amd64", Instant.now(), 900,
                new BigDecimal("1.400"), false, null)))
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("/benchmark cũng nằm sau shared secret")
    void benchmark_cung_can_secret() {
        HttpStatusCode status = http.post().uri(JudgeEndpoints.BENCHMARK)
                .body(new HostBenchmarkDto("mac-m1max-host", "arm64", Instant.now(), 630,
                        new BigDecimal("1.000"), false, null))
                .exchange((req, res) -> res.getStatusCode());

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpStatusCode postBenchmark(HostBenchmarkDto benchmark) {
        return http.post().uri(JudgeEndpoints.BENCHMARK)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(benchmark)
                .exchange((req, res) -> res.getStatusCode());
    }

    private HttpStatusCode postResult(JudgeResultDto result) {
        return http.post().uri(JudgeEndpoints.RESULT)
                .header(JudgeEndpoints.SECRET_HEADER, SECRET)
                .body(result)
                .exchange((req, res) -> res.getStatusCode());
    }

    private static JudgeResultDto result(long id, int attempt, Verdict verdict) {
        return new JudgeResultDto(id, attempt, verdict,
                verdict.isAccepted() ? 100 : 0, 100, verdict.isAccepted() ? null : 2,
                3, 234, 15_360, null, null,
                "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }
}
