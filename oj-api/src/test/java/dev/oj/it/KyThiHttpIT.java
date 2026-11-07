package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class KyThiHttpIT extends HttpIT {

    private static final Duration CHO = Duration.ofSeconds(20);
    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsUpdater updater;
    @Autowired ObjectMapper json;

    private final Instant bayGio = Instant.now();

    private long kyThi(Instant batDau, Instant ketThuc, Instant dongBang) {
        return kyThi(batDau, ketThuc, dongBang, true);
    }

    private long kyThi(Instant batDau, Instant ketThuc, Instant dongBang, boolean batBuocDangKy) {
        long id = contests.tao(new ContestRepository.ContestMoi("http-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, dongBang, 20, batBuocDangKy, true,
                ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private void nopAc(long contestId) {
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) "
                + "VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, now())
                """).param("u", USER_ID).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).update();
    }

    @Test
    @DisplayName("★ NB-03 · chưa đăng ký mà gọi thẳng POST /submissions trong giờ thi → 404, không tạo bài nộp")
    void nb03_chua_dang_ky_nop_thang_qua_api() {
        kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null);

        var res = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", bearerDev())
                .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                        "source", "int main(){}")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single())
                .isZero();
    }

    @Test
    @DisplayName("★ DK-06 · kỳ thi không bắt buộc đăng ký mà người dùng vẫn bấm đăng ký → 204, vô hại")
    void dk06_dang_ky_khi_khong_bat_buoc() {
        long id = kyThi(bayGio.plus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(4)), null,
                false);

        var res = goi(http.post().uri("/api/v1/contests/{id}/register", id)
                .header("Authorization", bearerDev()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(contests.daDangKy(id, USER_ID)).isTrue();
    }

    @Test
    @DisplayName("★ KG-06 · kỳ thi không bắt buộc đăng ký, đang chạy: ai đăng nhập cũng xem được đề mà không cần đăng ký")
    void kg06_khong_bat_buoc_thi_ai_dang_nhap_cung_xem_duoc() {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null,
                false);

        var res = goi(http.get().uri("/api/v1/problems/{code}", "A-PLUS-B")
                .header("Authorization", bearerDev()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(contests.daDangKy(id, USER_ID)).as("xem được dù chưa hề đăng ký").isFalse();
    }

    @Test
    @DisplayName("★ DB-11 · USER và SETTER gọi công bố bảng → 403, bảng vẫn đóng băng")
    void db11_sai_vai_tro_khong_cong_bo_duoc() {
        long id = contests.tao(new ContestRepository.ContestMoi("http-" + System.nanoTime(),
                "Thi thử", "ICPC", bayGio.minus(Duration.ofHours(3)),
                bayGio.minus(Duration.ofHours(1)), bayGio.minus(Duration.ofHours(2)), 20, true,
                false, ADMIN_ID));

        for (String token : List.of(bearerDev(), bearer(SETTER_ID, "setter", Role.SETTER))) {
            assertThat(goi(http.post().uri("/api/v1/contests/{id}/reveal", id)
                    .header("Authorization", token)).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(contests.timTheoId(id).orElseThrow().daCongBo()).isFalse();
    }

    @Test
    @DisplayName("★ TT-02 · /standings/stream: có bảng ngay lúc mở, có sự kiện khi ghi lô mới; người thường vẫn thấy bản chụp, ADMIN thấy bảng thật")
    void tt02_luong_sse_bang_xep_hang() throws Exception {
        long id = kyThi(bayGio.minus(Duration.ofHours(2)), bayGio.plus(Duration.ofHours(1)),
                bayGio.minus(Duration.ofMinutes(30)));
        jdbc.sql("""
                INSERT INTO contest_standings_frozen (contest_id, user_id, total_score,
                                                      penalty_seconds, solved_count)
                VALUES (:c, :u, 0, 0, 0)
                """).param("c", id).param("u", USER_ID).update();

        try (var admin = new Luong(id, bearer(ADMIN_ID, "admin", Role.ADMIN));
             var dev = new Luong(id, bearerDev())) {
            assertThat(admin.suKien(1)).contains("\"dongBang\":false");
            assertThat(dev.suKien(1)).contains("\"dongBang\":true");

            nopAc(id);
            updater.capNhat(contests.timTheoId(id).orElseThrow());

            assertThat(admin.coDong("\"soBaiDat\":1")).contains("\"dongBang\":false");
            assertThat(dev.suKien(2)).contains("\"dongBang\":true")
                    .doesNotContain("\"soBaiDat\":1");
        }
    }

    @Test
    @DisplayName("★ TT-03 · GET /standings trả đúng nội dung sự kiện SSE — đường dự phòng khi luồng đứt")
    void tt03_rest_cung_noi_dung_voi_sse() throws Exception {
        long id = kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)), null);
        nopAc(id);
        updater.capNhat(contests.timTheoId(id).orElseThrow());

        String quaRest = http.get().uri("/api/v1/contests/{id}/standings", id)
                .header("Authorization", bearerDev()).retrieve().body(String.class);
        assertThat(quaRest).contains("\"soBaiDat\":1").contains("\"cuaToi\":{");

        try (var dev = new Luong(id, bearerDev())) {
            assertThat(json.readTree(dev.suKien(1))).isEqualTo(json.readTree(quaRest));
        }
    }

    private final class Luong implements AutoCloseable {

        private final HttpClient client = HttpClient.newHttpClient();
        private final List<String> dong = new CopyOnWriteArrayList<>();

        Luong(long contestId, String bearer) throws Exception {
            HttpResponse<java.util.stream.Stream<String>> res = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/api/v1/contests/" + contestId + "/standings/stream"))
                            .header("Authorization", bearer).GET().build(),
                    HttpResponse.BodyHandlers.ofLines());
            assertThat(res.statusCode()).isEqualTo(200);
            Thread.ofPlatform().daemon().start(() -> {
                try (var than = res.body()) {
                    than.forEach(dong::add);
                } catch (UncheckedIOException khiDong) {
                    dong.add(": " + khiDong.getMessage());
                }
            });
        }

        String suKien(int thu) {
            return cho(() -> duLieu().size() >= thu ? duLieu().get(thu - 1) : null,
                    "sự kiện thứ " + thu);
        }

        String coDong(String can) {
            return cho(() -> duLieu().stream().filter(d -> d.contains(can)).findFirst()
                    .orElse(null), "sự kiện chứa " + can);
        }

        private List<String> duLieu() {
            return dong.stream().filter(d -> d.startsWith("data:"))
                    .map(d -> d.substring("data:".length())).toList();
        }

        private String cho(Supplier<String> tim, String moTa) {
            long han = System.nanoTime() + CHO.toNanos();
            while (System.nanoTime() < han) {
                String thay = tim.get();
                if (thay != null) {
                    return thay;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            throw new AssertionError("Không thấy " + moTa + " sau " + CHO + ".\nĐã nhận:\n"
                    + String.join("\n", dong));
        }

        @Override
        public void close() {
            client.shutdownNow();
        }
    }
}
