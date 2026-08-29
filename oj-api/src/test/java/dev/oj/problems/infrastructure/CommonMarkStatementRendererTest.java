package dev.oj.problems.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Render đề bài — FR-PROB-02, Bước 4.9.
 *
 * <h2>Vì sao nửa số ca ở đây là ca tấn công</h2>
 * Đề bài do SETTER soạn và hiển thị cho <b>mọi thí sinh đang thi</b>. Một thẻ {@code <script>}
 * lọt qua bộ render là XSS trên trang mà cả kỳ thi đang mở, và nó đọc được access token trong
 * bộ nhớ trình duyệt của từng người.
 *
 * <p>Mối đe doạ không cần một SETTER cố ý phá: một tài khoản SETTER bị chiếm là đủ.
 */
class CommonMarkStatementRendererTest {

    private final CommonMarkStatementRenderer renderer = new CommonMarkStatementRenderer();

    @Nested
    @DisplayName("★ Chống XSS")
    class ChongXss {

        @Test
        @DisplayName("thẻ script bị escape thành văn bản, không thành thẻ")
        void the_script_bi_escape() {
            String html = renderer.render("Trước <script>alert(document.cookie)</script> sau");

            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        /**
         * ★ Điều cần canh là <b>thẻ mở</b>, không phải chuỗi ký tự.
         *
         * <p>Bản đầu của ca này còn khẳng định output không chứa chữ {@code "onerror"} — và
         * nó đỏ, đúng lý: {@code &lt;img src=x onerror=alert(1)&gt;} <i>có</i> chứa chữ đó,
         * dưới dạng <b>văn bản đã escape</b>. Trình duyệt hiển thị nó như một dòng chữ và
         * không chạy gì cả.
         *
         * <p>Ghi lại vì đó là một cái bẫy dễ lặp lại: một phép kiểm XSS bắt theo từ khoá sẽ
         * vừa báo động giả (một đề bàn về XSS không nạp được) vừa bỏ sót thật (một vector
         * chưa có trong danh sách từ khoá). Thứ quyết định an toàn là <b>ký tự {@code <} có
         * còn là ký tự {@code <} hay không</b>.
         */
        @Test
        @DisplayName("mọi lối HTML thô đều bị escape, không chỉ script")
        void moi_the_html_deu_bi_escape() {
            for (String doc : new String[]{
                    "<img src=x onerror=alert(1)>",
                    "<iframe src=\"//ke-tan-cong.test\"></iframe>",
                    "<svg/onload=alert(1)>",
                    "<style>body{display:none}</style>",
                    "<a href=\"#\" onclick=\"alert(1)\">bấm</a>"}) {
                String html = renderer.render(doc);

                assertThat(html)
                        .describedAs("đầu vào: %s — không thẻ mở nào được sống sót", doc)
                        .doesNotContain("<img", "<iframe", "<svg", "<style", "<a ");
                assertThat(html)
                        .describedAs("đầu vào: %s — dấu < phải bị escape", doc)
                        .contains("&lt;");
            }
        }

        @Test
        @DisplayName("★ liên kết javascript: bị vô hiệu hoá — nó không chứa HTML nào cả")
        void lien_ket_javascript_bi_chan() {
            // escapeHtml không cứu được ca này: đây là cú pháp Markdown hợp lệ. Chốt là
            // sanitizeUrls(true).
            String html = renderer.render("[bấm vào đây](javascript:alert(document.cookie))");

            assertThat(html).doesNotContain("javascript:alert");
        }

        @Test
        @DisplayName("liên kết http và https bình thường thì giữ nguyên")
        void lien_ket_binh_thuong_van_chay() {
            String html = renderer.render("[tài liệu](https://vi.wikipedia.org/wiki/Thuật_toán)");

            assertThat(html).contains("https://vi.wikipedia.org");
        }
    }

    @Nested
    @DisplayName("Markdown thường dùng trong đề")
    class MarkdownThuongDung {

        @Test
        @DisplayName("đậm, mã inline, khối mã, danh sách, bảng")
        void cu_phap_co_ban() {
            String html = renderer.render("""
                    **Đầu vào:** một dòng chứa `a b`.

                    ```
                    1 2
                    ```

                    - điểm một
                    - điểm hai

                    | a | b |
                    |---|---|
                    | 1 | 2 |
                    """);

            assertThat(html).contains("<strong>", "<code>", "<pre>", "<ul>", "<table>");
        }

        @Test
        @DisplayName("★ đoạn LaTeX đi qua nguyên vẹn để KaTeX vẽ ở trình duyệt")
        void latex_di_qua_nguyen_ven() {
            String html = renderer.render("Cho $n \\le 10^6$ và $$\\sum_{i=1}^{n} a_i$$");

            // Server không vẽ công thức — quyết định của Bước 4.9, xem javadoc của renderer.
            // Nhưng nó phải KHÔNG làm hỏng cú pháp, nếu không thì KaTeX không nhận ra.
            assertThat(html).contains("$n \\le 10^6$");
            assertThat(html).contains("\\sum_{i=1}^{n} a_i");
        }

        @Test
        @DisplayName("dấu tiếng Việt không bị mã hoá thành entity")
        void tieng_viet_nguyen_ven() {
            String html = renderer.render("Cho hai số nguyên `a` và `b`. In ra tổng của chúng.");

            assertThat(html).contains("Cho hai số nguyên", "In ra tổng của chúng");
        }

        @Test
        @DisplayName("đề rỗng hoặc null không làm nổ")
        void dau_vao_rong() {
            assertThat(renderer.render(null)).isEmpty();
            assertThat(renderer.render("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("Khoá cache")
    class KhoaCache {

        @Test
        @DisplayName("★ version nhắc cả thư viện lẫn cách xử lý toán")
        void version_noi_du_hai_thu() {
            // Cả hai đều đổi kết quả người dùng thấy. Nếu version chỉ nhắc commonmark thì
            // ngày chuyển LaTeX sang render ở server, bản HTML cũ vẫn được phục vụ mãi mãi.
            assertThat(renderer.version()).contains("commonmark").contains("katex");
        }

        @Test
        @DisplayName("cùng đầu vào cho cùng đầu ra — điều kiện để cache theo hash có nghĩa")
        void render_on_dinh() {
            String doc = "**A** + *B* = `C`";

            assertThat(renderer.render(doc)).isEqualTo(renderer.render(doc));
        }
    }
}
