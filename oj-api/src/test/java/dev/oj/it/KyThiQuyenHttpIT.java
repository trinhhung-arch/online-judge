package dev.oj.it;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class KyThiQuyenHttpIT extends HttpIT {

    private static final String NGUON = "a".repeat(64);

    private static final Set<String> TRUONG_CHI_TIET_BAI_NOP = Set.of("submissionId", "problemId",
            "languageId", "status", "verdict", "explanation", "score", "maxScore", "timeMs",
            "memoryKb", "measurementNote", "failedTestOrdinal", "compileLog", "attempt",
            "createdAt", "judgedAt");

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;

    private final Instant bayGio = Instant.now();

    private Map<String, Object> kyThiMoi(String slug) {
        Instant batDau = bayGio.plus(Duration.ofDays(1));
        return Map.of("slug", slug, "title", "Thi thử", "format", "ICPC",
                "startsAt", batDau.toString(), "endsAt", batDau.plus(Duration.ofHours(3)).toString());
    }

    private long kyThi(Instant batDau, Instant ketThuc) {
        long id = contests.tao(new ContestRepository.ContestMoi("quyen-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, null, 20, true, true, SETTER_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private long dangChay() {
        return kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)));
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    private long saiOTest(long contestId, long userId, int test) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """)
                .param("sha", NGUON).update();
        return jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, failed_test_ordinal,
                                         judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'WA', 0, 100, :test, now())
                RETURNING id
                """).param("u", userId).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).param("test", test).query(Long.class).single();
    }

    private ResponseEntity<Map<String, Object>> xemBai(long submissionId, String token) {
        return goi(http.get().uri("/api/v1/submissions/{id}", submissionId)
                .header("Authorization", token));
    }

    @Test
    @DisplayName("★ AT-01 · SETTER tạo kỳ thi hợp lệ qua API → 201, created_by là người gọi, audit CONTEST_CREATED mang tên người gọi")
    void at01_setter_tao_ky_thi() {
        var res = goi(http.post().uri("/api/v1/contests")
                .header("Authorization", bearer(SETTER_ID, "setter", Role.SETTER))
                .body(kyThiMoi("tao-hop-le")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long id = ((Number) res.getBody().get("contestId")).longValue();
        assertThat(contests.timTheoId(id)).hasValueSatisfying(c -> {
            assertThat(c.slug()).isEqualTo("tao-hop-le");
            assertThat(c.createdBy()).isEqualTo(SETTER_ID);
        });
        assertThat(jdbc.sql("""
                SELECT actor_id FROM audit_log
                 WHERE action = 'CONTEST_CREATED' AND entity_type = 'contest' AND entity_id = :id
                """).param("id", id).query(Long.class).list()).containsExactly(SETTER_ID);
    }

    @Test
    @DisplayName("★ AT-08 · USER tạo kỳ thi qua API → 403 auth.thieu_quyen, không có kỳ thi nào được tạo")
    void at08_user_khong_tao_duoc() {
        var res = goi(http.post().uri("/api/v1/contests").header("Authorization", bearerDev())
                .body(kyThiMoi("user-tao")));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody()).containsEntry("code", "auth.thieu_quyen");
        assertThat(contests.timTheoSlug("user-tao")).isEmpty();
    }

    @Test
    @DisplayName("★ DK-05 · khách gọi đăng ký → 401 auth.chua_dang_nhap, không có lượt đăng ký nào")
    void dk05_khach_dang_ky() {
        long id = kyThi(bayGio.plus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(4)));

        var res = goi(http.post().uri("/api/v1/contests/{id}/register", id));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).containsEntry("code", "auth.chua_dang_nhap");
        assertThat(jdbc.sql("SELECT count(*) FROM contest_registrations WHERE contest_id = :c")
                .param("c", id).query(Integer.class).single()).isZero();
    }

    @Test
    @DisplayName("★ NB-07 · nộp hai bài liền nhau trong giờ thi → bài đầu 202 vào kỳ thi, bài hai 429 kèm Retry-After như ngoài giờ thi")
    void nb07_gioi_han_nop_trong_gio_thi() {
        long id = dangChay();
        contests.dangKy(id, USER_ID, bayGio);
        Map<String, Object> bai = Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                "source", "// EXPECT: AC\nint main(){}");

        var lanDau = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", bearerDev()).body(bai));
        var lanHai = goi(http.post().uri("/api/v1/submissions")
                .header("Authorization", bearerDev()).body(bai));

        assertThat(lanDau.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(lanHai.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(lanHai.getBody()).containsEntry("code", "submission.rate_limited");
        assertThat(lanHai.getHeaders().getFirst("Retry-After")).isNotNull();
        assertThat(jdbc.sql("SELECT contest_id FROM submissions").query(Long.class).list())
                .as("chỉ bài đầu được ghi, và nó thuộc kỳ thi").containsExactly(id);
    }

    @Test
    @DisplayName("★ LT-04 · trong giờ thi: thí sinh khác và chủ kỳ thi xem bài nộp của một thí sinh → 404 như id không có thật; chủ bài và ADMIN → 200")
    void lt04_bai_nop_cua_thi_sinh_khac() {
        long id = dangChay();
        long thiSinhKhac = nguoi("thi-sinh-khac");
        contests.dangKy(id, USER_ID, bayGio);
        contests.dangKy(id, thiSinhKhac, bayGio);
        long bai = saiOTest(id, USER_ID, 3);
        String cuaThiSinhKhac = bearer(thiSinhKhac, "thi-sinh-khac", Role.USER);

        var thiSinhKhacXem = xemBai(bai, cuaThiSinhKhac);
        var chuKyThiXem = xemBai(bai, bearer(SETTER_ID, "setter", Role.SETTER));

        assertThat(thiSinhKhacXem.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(chuKyThiXem.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thiSinhKhacXem.getBody().get("code"))
                .as("không phân biệt được với một id không có thật")
                .isEqualTo(xemBai(999_999L, cuaThiSinhKhac).getBody().get("code"));
        assertThat(xemBai(bai, bearerDev()).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(xemBai(bai, bearer(ADMIN_ID, "admin", Role.ADMIN)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("★ LT-05 · bài sai ở test ẩn trong giờ thi: đề NONE không lộ số thứ tự test, kể cả trong lời giải thích; đề TEST_INDEX chỉ lộ số thứ tự, không trường nào chở nội dung test")
    void lt05_phan_hoi_theo_feedback_level() {
        long id = dangChay();
        contests.dangKy(id, USER_ID, bayGio);
        long bai = saiOTest(id, USER_ID, 3);

        datMucPhanHoi("NONE");
        Map<String, Object> giau = xemBai(bai, bearerDev()).getBody();
        datMucPhanHoi("TEST_INDEX");
        Map<String, Object> chiSoThuTu = xemBai(bai, bearerDev()).getBody();

        assertThat(giau.get("failedTestOrdinal")).isNull();
        assertThat((String) giau.get("explanation")).doesNotContain("test 3");
        assertThat(chiSoThuTu.get("failedTestOrdinal")).isEqualTo(3);
        assertThat((String) chiSoThuTu.get("explanation")).contains("test 3");
        assertThat(giau.keySet()).isSubsetOf(TRUONG_CHI_TIET_BAI_NOP);
        assertThat(chiSoThuTu.keySet())
                .as("thêm trường mới vào chi tiết bài nộp thì phải xét lại ca này")
                .isSubsetOf(TRUONG_CHI_TIET_BAI_NOP);
    }

    private void datMucPhanHoi(String muc) {
        jdbc.sql("UPDATE problems SET feedback_level = :muc WHERE id = :p")
                .param("muc", muc).param("p", PROBLEM_ID).update();
    }
}
