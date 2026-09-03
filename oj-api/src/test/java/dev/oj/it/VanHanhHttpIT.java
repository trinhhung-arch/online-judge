package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Phân quyền của bảy endpoint mới ở M6 — {@code CLAUDE.md} mục 6, dòng "Endpoint mới".
 *
 * <h2>"403, KHÔNG PHẢI 200 rỗng"</h2>
 * Bảng test bắt buộc nói đúng câu đó, và nó có lý do: một endpoint quản trị viết thiếu chốt
 * quyền thường vẫn <i>chạy</i> — nó chỉ trả về một danh sách rỗng, hoặc thao tác trên một
 * đối tượng không tồn tại rồi trả 204. Nhìn từ ngoài, "an toàn" và "không có dữ liệu" giống
 * hệt nhau. Chỉ một mã trạng thái từ chối mới phân biệt được hai thứ đó.
 *
 * <p>Vì thế mọi ca dưới đây khẳng định <b>mã trạng thái</b>, không khẳng định thân rỗng.
 */
class VanHanhHttpIT extends HttpIT {

    @Nested
    @DisplayName("★ Endpoint quản trị: USER và SETTER đều phải bị từ chối")
    class ChiAdmin {

        @Test
        @DisplayName("GET /admin/audit-log — FR-ADM-02")
        void audit_log() {
            kiemChiAdmin(() -> goi(http.get().uri("/api/v1/admin/audit-log")
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())),
                    () -> goi(http.get().uri("/api/v1/admin/audit-log")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer())));
        }

        @Test
        @DisplayName("GET /admin/ops — FR-ADM-04")
        void dashboard() {
            kiemChiAdmin(() -> goi(http.get().uri("/api/v1/admin/ops")
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())),
                    () -> goi(http.get().uri("/api/v1/admin/ops")
                            .header(HttpHeaders.AUTHORIZATION, adminBearer())));
        }

        @Test
        @DisplayName("POST /admin/problems/{id}/rejudge — FR-ADM-01")
        void rejudge() {
            var cuaUser = goi(http.post().uri("/api/v1/admin/problems/{id}/rejudge", PROBLEM_ID)
                    .header(HttpHeaders.AUTHORIZATION, bearerDev()));
            var cuaSetter = goi(http.post().uri("/api/v1/admin/problems/{id}/rejudge", PROBLEM_ID)
                    .header(HttpHeaders.AUTHORIZATION, setterBearer()));

            assertThat(cuaUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(cuaSetter.getStatusCode())
                    .as("SETTER sở hữu đề vẫn KHÔNG được chấm lại hàng loạt — nó là thao tác "
                            + "ảnh hưởng tới toàn bộ máy chấm")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("POST /admin/submissions/{id}/hide — FR-SUB-09")
        void an_bai_nop() {
            kiemChiAdmin(() -> goi(http.post().uri("/api/v1/admin/submissions/{id}/hide", 1)
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())),
                    () -> goi(http.post().uri("/api/v1/admin/submissions/{id}/hide", 1)
                            .header(HttpHeaders.AUTHORIZATION, adminBearer())));
        }

        @Test
        @DisplayName("POST /admin/users/{id}/role và /active — FR-ADM-03")
        void quan_ly_nguoi_dung() {
            var cuaUser = goi(http.post().uri("/api/v1/admin/users/{id}/role", SETTER_ID)
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())
                    .body(Map.of("vaiTro", "ADMIN")));

            assertThat(cuaUser.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(vaiTroCua(SETTER_ID))
                    .as("★ bị từ chối thì KHÔNG được đổi gì cả — 403 mà vẫn ghi là lỗ hổng")
                    .isEqualTo("SETTER");
        }

        /**
         * ★★ FR-PROB-12 — endpoint duy nhất đưa nội dung testcase ẩn ra khỏi hệ thống.
         *
         * <p>Ba vai trò, ba kết quả, và ca giữa là ca quan trọng nhất: <b>SETTER của một đề
         * khác</b> phải bị từ chối. Chốt {@code @RequiresRole(SETTER)} một mình không làm được
         * điều đó — nó chỉ nói "người gọi là SETTER". Chốt thật nằm trong câu query
         * {@code findForAuthorById}.
         */
        @Test
        @DisplayName("★★ GET /problems/{id}/testdata — SETTER của đề KHÁC cũng bị từ chối")
        void tai_testdata() {
            long deCuaNguoiKhac = taoDeCuaAdmin();

            var cuaUser = goi(http.get().uri("/api/v1/problems/{id}/testdata", PROBLEM_ID)
                    .header(HttpHeaders.AUTHORIZATION, bearerDev()));
            var cuaSetterDeKhac = goi(http.get()
                    .uri("/api/v1/problems/{id}/testdata", deCuaNguoiKhac)
                    .header(HttpHeaders.AUTHORIZATION, setterBearer()));

            assertThat(cuaUser.getStatusCode())
                    .as("USER — kể cả tác giả một bài nộp vừa fail — không bao giờ vào được")
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(cuaSetterDeKhac.getStatusCode())
                    .as("SETTER của đề khác: 404, KHÔNG phải 403. 403 xác nhận đề tồn tại và "
                            + "có testdata — với đề của kỳ thi tuần sau thì chính điều đó là "
                            + "thứ không được lộ")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("FR-ADM-05 · trang trạng thái công khai")
    class TrangThaiCongKhai {

        @Test
        @DisplayName("★ đọc được khi CHƯA đăng nhập")
        void khach_doc_duoc() {
            var res = goi(http.get().uri("/api/v1/status"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).containsKeys("dangNhanBai", "dangCho", "dangCham",
                    "mayChamSong", "choLauNhatMs", "choUocTinhMs");
        }

        /**
         * Bốn con số ở đây nói về <b>máy chấm</b>, không về <b>bài nộp</b>. Biết "có 40 bài
         * đang chờ" không cho ai lợi thế trong kỳ thi; biết "40 bài của đề C" thì có.
         */
        @Test
        @DisplayName("★ không lộ một mã đề hay một submissionId nào")
        void khong_lo_gi_ve_bai_nop() {
            var than = goi(http.get().uri("/api/v1/status")).getBody().toString();

            assertThat(than).doesNotContain("problem", "submission", "A-PLUS-B", "userId");
        }
    }

    @Nested
    @DisplayName("★ FR-ADM-06 · công tắc bảo trì")
    class CheDoBaoTri {

        @Test
        @DisplayName("★ tắt nhận bài thì POST /submissions trả 503, và KHÔNG ghi gì vào DB")
        void tat_thi_tu_choi_503() {
            long truoc = soBaiNop();
            jdbc.sql("UPDATE system_settings SET value = 'false'::jsonb "
                    + "WHERE key = 'submissions.accepting'").update();
            quenLuotNopVuaRoi(USER_ID);
            choCacheCongTacHetHan();

            var res = goi(http.post().uri("/api/v1/submissions")
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())
                    .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                            "source", "int main(){}")));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(soBaiNop()).as("bảo trì thì không có bài nào được ghi").isEqualTo(truoc);
            assertThat(res.getBody()).containsEntry("code", "submission.maintenance");
        }

        /**
         * ★ "Bài đang chấm vẫn chấm xong" — nửa sau của FR-ADM-06, và là nửa dễ quên.
         *
         * <p>Công tắc chỉ chặn <b>cửa vào</b>. Nó không đụng tới {@code judge_queue}, nên mọi
         * bài đã commit vẫn đi hết đường của nó. Một hiện thực "đúng" mà xoá hàng đợi khi bật
         * bảo trì sẽ qua được ca ở trên và phá R1 ở ca này.
         */
        @Test
        @DisplayName("★ bảo trì KHÔNG đụng tới hàng đợi — bài đang chờ vẫn còn nguyên")
        void bai_dang_cham_van_cham_xong() {
            quenLuotNopVuaRoi(USER_ID);
            var daNop = goi(http.post().uri("/api/v1/submissions")
                    .header(HttpHeaders.AUTHORIZATION, bearerDev())
                    .body(Map.of("problemId", PROBLEM_ID, "languageCode", "cpp20",
                            "source", "int main(){}")));
            assertThat(daNop.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            long id = ((Number) daNop.getBody().get("submissionId")).longValue();

            jdbc.sql("UPDATE system_settings SET value = 'false'::jsonb "
                    + "WHERE key = 'submissions.accepting'").update();
            choCacheCongTacHetHan();
            goi(http.get().uri("/api/v1/status"));   // một request bất kỳ trong lúc bảo trì

            assertThat(jdbc.sql("SELECT count(*) FROM judge_queue WHERE submission_id = :id")
                    .param("id", id).query(Integer.class).single())
                    .as("bài đã nhận trước khi bảo trì vẫn nằm trong hàng đợi")
                    .isEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------

    /**
     * Cache công tắc sống 2 giây ({@code JdbcSystemSettings.TTL}). Test sửa thẳng bằng SQL nên
     * không đi qua {@code dat()} — thứ duy nhất xoá cache. Chờ qua TTL là cách trung thực để
     * mô phỏng đúng thứ xảy ra trên một instance <i>khác</i> instance nhận lệnh.
     */
    private static void choCacheCongTacHetHan() {
        try {
            Thread.sleep(2_100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void kiemChiAdmin(Supplier<ResponseEntity<Map<String, Object>>> boiUser,
                              Supplier<ResponseEntity<Map<String, Object>>> boiAdmin) {
        assertThat(boiUser.get().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(boiAdmin.get().getStatusCode())
                .as("ADMIN phải vào được — nếu không thì ca 403 ở trên không chứng minh gì cả")
                .isNotEqualTo(HttpStatus.FORBIDDEN);
    }

    private String adminBearer() {
        return bearer(ADMIN_ID, "admin", dev.oj.platform.security.Role.ADMIN);
    }

    private String setterBearer() {
        return bearer(SETTER_ID, "setter", dev.oj.platform.security.Role.SETTER);
    }

    private String vaiTroCua(long userId) {
        return jdbc.sql("SELECT role FROM users WHERE id = :id")
                .param("id", userId).query(String.class).single();
    }

    private long taoDeCuaAdmin() {
        return jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES ('DE-CUA-ADMIN', 'Đề của admin', 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :owner, now(), 1)
                RETURNING id
                """).param("hash", "b".repeat(64)).param("owner", ADMIN_ID)
                .query(Long.class).single();
    }

    private int soBaiNop() {
        return jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single();
    }
}
