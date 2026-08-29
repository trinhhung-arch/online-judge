package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Soạn đề qua HTTP thật — FR-PROB-01, 02, 07, 08, 09. Bước 4.9.
 *
 * <p>Ba nhóm ca, và mỗi nhóm canh một thứ khác nhau: <b>ai được làm</b> (phân quyền và sở
 * hữu), <b>khi nào được làm</b> (xuất bản cần testdata), và <b>người đọc thấy gì</b> (HTML đã
 * escape, danh sách chỉ có đề đã xuất bản).
 */
class ProblemAuthoringIT extends HttpIT {

    private static Map<String, Object> deMoi(String code) {
        var m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        m.put("title", "Đề thử nghiệm");
        m.put("statementMd", "Cho `a` và `b`. In ra $a+b$.");
        m.put("timeLimitMs", 1000);
        m.put("memoryLimitKb", 262_144);
        m.put("feedbackLevel", "NONE");
        return m;
    }

    private long tao(String code, String handle) {
        var res = goi(http.post().uri("/api/v1/problems")
                .header("Authorization", "Bearer " + tokenCua(handle))
                .body(deMoi(code)));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) res.getBody().get("problemId")).longValue();
    }

    // =========================================================================

    @Nested
    @DisplayName("★ Ai được soạn đề")
    class AiDuocSoan {

        @Test
        @DisplayName("USER thường gọi POST /problems → 403, và không có đề nào được tạo")
        void user_khong_tao_duoc_de() {
            var res = goi(http.post().uri("/api/v1/problems")
                    .header("Authorization", "Bearer " + tokenCua("dev"))
                    .body(deMoi("USER-THU")));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(res.getBody()).containsEntry("code", "auth.thieu_quyen");
            assertThat(jdbc.sql("SELECT count(*) FROM problems WHERE code = :c")
                    .param("c", "USER-THU").query(Integer.class).single()).isZero();
        }

        @Test
        @DisplayName("SETTER tạo được, và đề mới LUÔN ở DRAFT")
        void setter_tao_duoc_va_luon_draft() {
            long id = tao("DE-MOI-1", "setter");

            assertThat(jdbc.sql("SELECT status FROM problems WHERE id = :id")
                    .param("id", id).query(String.class).single()).isEqualTo("DRAFT");
        }

        @Test
        @DisplayName("★ SETTER sửa đề của người khác → 404, không phải 403")
        void khong_sua_duoc_de_nguoi_khac() {
            long cuaSetter = tao("DE-CUA-SETTER", "setter");

            // admin đóng vai một SETTER khác thì sẽ qua (ADMIN thấy tất cả), nên ta kiểm
            // bằng chiều ngược lại: nâng một tài khoản USER lên SETTER rồi thử.
            jdbc.sql("UPDATE users SET role = 'SETTER' WHERE id = :id")
                    .param("id", USER_ID).update();

            var res = goi(http.put().uri("/api/v1/problems/" + cuaSetter)
                    .header("Authorization", "Bearer " + tokenCua("dev"))
                    .body(deMoi("DE-CUA-SETTER")));

            // 403 ở đây là xác nhận "có một đề id này" — và đề chưa xuất bản rất thường là
            // đề của contest tuần sau (FR-PROB-08).
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("ADMIN sửa được đề của mọi người")
        void admin_sua_duoc_tat_ca() {
            long id = tao("DE-CHO-ADMIN", "setter");
            var sua = deMoi("DE-CHO-ADMIN");
            sua.put("title", "Tiêu đề do ADMIN đổi");

            var res = goi(http.put().uri("/api/v1/problems/" + id)
                    .header("Authorization", "Bearer " + tokenCua("admin")).body(sua));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(jdbc.sql("SELECT title FROM problems WHERE id = :id")
                    .param("id", id).query(String.class).single())
                    .isEqualTo("Tiêu đề do ADMIN đổi");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-PROB-08 · xuất bản")
    class XuatBan {

        @Test
        @DisplayName("★ đề chưa có testdata KHÔNG xuất bản được → 409")
        void chua_co_testdata_thi_khong_xuat_ban() {
            long id = tao("DE-CHUA-TEST", "setter");

            var res = goi(http.post().uri("/api/v1/problems/" + id + "/publish")
                    .header("Authorization", "Bearer " + tokenCua("setter")));

            // Một đề PUBLISHED với current_testdata_version = 0 sẽ nhận bài nộp và cho IE
            // trên MỌI bài — người dùng thấy hệ thống hỏng, tác giả không biết mình quên gì.
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(res.getBody()).containsEntry("code", "problem.chua_co_testdata");
        }

        @Test
        @DisplayName("có testdata thì xuất bản được và xuất hiện trong danh sách công khai")
        void co_testdata_thi_xuat_ban_duoc() {
            long id = tao("DE-CO-TEST", "setter");
            jdbc.sql("UPDATE problems SET current_testdata_version = 1 WHERE id = :id")
                    .param("id", id).update();

            assertThat(goi(http.post().uri("/api/v1/problems/" + id + "/publish")
                    .header("Authorization", "Bearer " + tokenCua("setter")))
                    .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(danhSachCong()).contains("DE-CO-TEST");
        }

        @Test
        @DisplayName("gỡ xuống thì biến khỏi danh sách nhưng bản ghi còn nguyên")
        void go_xuong() {
            assertThat(goi(http.post().uri("/api/v1/problems/" + PROBLEM_ID + "/retire")
                    .header("Authorization", "Bearer " + tokenCua("setter")))
                    .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(danhSachCong()).doesNotContain("A-PLUS-B");
            assertThat(jdbc.sql("SELECT count(*) FROM problems WHERE id = :id")
                    .param("id", PROBLEM_ID).query(Integer.class).single()).isEqualTo(1);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ Người đọc thấy gì")
    class NguoiDocThayGi {

        @Test
        @DisplayName("★ FR-PROB-02 — statementHtml đã render và đã escape HTML thô")
        void html_da_render_va_da_escape() {
            long id = tao("DE-XSS", "setter");
            var sua = deMoi("DE-XSS");
            sua.put("statementMd", "**Đậm** và <script>alert(1)</script>");
            goi(http.put().uri("/api/v1/problems/" + id)
                    .header("Authorization", "Bearer " + tokenCua("setter")).body(sua));

            var de = goi(http.get().uri("/api/v1/problems/" + id + "/edit")
                    .header("Authorization", "Bearer " + tokenCua("setter"))).getBody();

            assertThat((String) de.get("statementHtml"))
                    .contains("<strong>Đậm</strong>")
                    .doesNotContain("<script>");
            // Markdown gốc vẫn trả về nguyên vẹn — trang soạn đề cần đúng thứ tác giả đã gõ.
            assertThat((String) de.get("statement")).contains("<script>");
        }

        @Test
        @DisplayName("★ đề DRAFT không xuất hiện trong danh sách công khai")
        void draft_khong_lo_ra() {
            tao("DE-BI-MAT", "setter");

            assertThat(danhSachCong())
                    .describedAs("một đề DRAFT rất thường là đề của contest tuần sau")
                    .doesNotContain("DE-BI-MAT");
        }

        @Test
        @DisplayName("★ danh sách KHÔNG cõng theo nội dung đề — bất biến #8 và mục 15")
        void danh_sach_khong_cong_noi_dung() {
            var res = goi(http.get().uri("/api/v1/problems"));

            assertThat(res.getBody()).containsKey("items");
            assertThat(res.getBody().toString())
                    .doesNotContain("statementMd", "statementHtml", "statement=");
        }

        @Test
        @DisplayName("xin 1000 dòng thì trả về trần 50, không trả lỗi")
        void xin_qua_nhieu_thi_cat_xuong() {
            var res = goi(http.get().uri("/api/v1/problems?size=1000"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("khách chưa đăng nhập vẫn xem được danh sách")
        void khach_xem_duoc() {
            assertThat(goi(http.get().uri("/api/v1/problems")).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("lọc 'đã giải' cần đăng nhập — từ chối rõ ràng thay vì nói dối")
        void loc_da_giai_can_dang_nhap() {
            var res = goi(http.get().uri("/api/v1/problems?daGiai=true"));

            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(res.getBody())
                    .containsEntry("code", "problem.loc_da_giai_can_dang_nhap");
        }
    }

    private String danhSachCong() {
        return String.valueOf(goi(http.get().uri("/api/v1/problems")).getBody());
    }
}
