package dev.oj.it;

import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class NopBaiGioiHanHttpIT extends HttpIT {

    private static final String NOP = "/api/v1/submissions";
    private static final String KHOA = ResetGiuaCacTest.KHOA_RATE_LIMIT + USER_ID;

    @Test
    @DisplayName("★ NH-06 · source 5 MB → 400 submission.source_too_large, không 500, không ghi gì")
    void source_5MB() {
        var res = nop(bearerDev(), PROBLEM_ID, "// EXPECT: AC\n" + "a".repeat(5 * 1024 * 1024));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "submission.source_too_large");
        assertThat(soBaiNop()).isZero();
    }

    @Test
    @DisplayName("★ NH-09 · đề nháp (kể cả chủ đề nộp) và đề chưa có testdata → 404 cùng mã với đề không tồn tại, không ghi gì")
    void de_nhap_va_de_chua_co_testdata() {
        long nhap = taoDe("DE-NHAP", "DRAFT", 1, "c");
        long chuaCoTest = taoDe("DE-CHUA-TEST", "PUBLISHED", 0, "d");
        Object maKhongTonTai = nop(bearerDev(), 999_999, "int main(){}").getBody().get("code");

        for (var ca : List.of(
                new Object[]{"người khác nộp đề nháp", bearerDev(), nhap},
                new Object[]{"chủ đề nộp đề nháp của mình", bearer(SETTER_ID, "setter", Role.SETTER), nhap},
                new Object[]{"đề đã xuất bản nhưng chưa có testdata", bearerDev(), chuaCoTest})) {
            var res = nop((String) ca[1], (long) ca[2], "int main(){}");
            assertThat(res.getStatusCode()).as((String) ca[0]).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(res.getBody().get("code")).as((String) ca[0]).isEqualTo(maKhongTonTai);
        }
        assertThat(soBaiNop()).isZero();
    }

    @Test
    @DisplayName("★ NH-13 · Retry-After làm tròn LÊN theo thời gian còn lại, ít nhất 1 giây; hết lượt chờ thì nộp được")
    void retry_after_lam_tron_len() {
        redis.opsForValue().set(KHOA, "1", Duration.ofMillis(9_200));
        var conChinGiay = nop(bearerDev(), PROBLEM_ID, "int main(){}");
        assertThat(conChinGiay.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(conChinGiay.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("10");

        redis.opsForValue().set(KHOA, "1", Duration.ofMillis(300));
        var conMotChut = nop(bearerDev(), PROBLEM_ID, "int main(){}");
        assertThat(conMotChut.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(conMotChut.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("không bao giờ bảo client chờ 0 giây rồi lại chặn").isEqualTo("1");

        redis.delete(KHOA);
        assertThat(nop(bearerDev(), PROBLEM_ID, "int main(){}").getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(soBaiNop()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ NH-14 · bấm đúp: hai request cùng lúc của một người → đúng một 202 và một 429")
    void bam_dup() throws InterruptedException {
        String token = bearerDev();
        CountDownLatch xuatPhat = new CountDownLatch(1);
        List<Integer> ketQua = new CopyOnWriteArrayList<>();
        List<Thread> luong = List.of(1, 2).stream().map(i -> Thread.ofPlatform().start(() -> {
            try {
                xuatPhat.await(5, TimeUnit.SECONDS);
                ketQua.add(nop(token, PROBLEM_ID, "int main(){}").getStatusCode().value());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        })).toList();

        xuatPhat.countDown();
        for (Thread t : luong) {
            t.join(Duration.ofSeconds(20));
        }

        assertThat(ketQua).containsExactlyInAnyOrder(202, 429);
        assertThat(soBaiNop()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ LS-06 · lọc lịch sử theo verdict không tồn tại → 400 có mã, không 500")
    void verdict_la_trong_bo_loc() {
        var res = goi(http.get().uri(NOP + "?verdict=XYZ")
                .header(HttpHeaders.AUTHORIZATION, bearerDev()));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "request.invalid_parameter");
    }

    private ResponseEntity<Map<String, Object>> nop(String token, long problemId, String source) {
        return goi(http.post().uri(NOP)
                .header(HttpHeaders.AUTHORIZATION, token)
                .body(Map.of("problemId", problemId, "languageCode", "cpp20", "source", source)));
    }

    private long taoDe(String ma, String trangThai, int phienBanTest, String kyTuHash) {
        return jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES (:ma, :ma, 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', :trangThai, :chu,
                        CASE WHEN :trangThai = 'PUBLISHED' THEN now() END, :phienBan)
                RETURNING id
                """).param("ma", ma).param("hash", kyTuHash.repeat(64))
                .param("trangThai", trangThai).param("chu", SETTER_ID)
                .param("phienBan", phienBanTest)
                .query(Long.class).single();
    }

    private int soBaiNop() {
        return jdbc.sql("""
                SELECT count(*)::int FROM submissions
                """).query(Integer.class).single();
    }
}
