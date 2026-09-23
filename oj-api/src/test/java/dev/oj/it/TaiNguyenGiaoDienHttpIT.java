package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ static/vendor/ qua Tomcat thật (rà soát 2026-09-24, F3).
 *
 * <p>{@code TaiNguyenGiaoDienTest} đọc tệp trên đĩa; ca ở đây đo phần chỉ server trả lời được:
 * đường dẫn có {@code @} ({@code @codemirror/view@6.34.1}) có được phục vụ không, và với MIME
 * nào — trình duyệt TỪ CHỐI chạy một ES module mang MIME không phải JavaScript, và lỗi ấy chỉ
 * hiện trong console. Cùng lúc: header CSP thật gửi ra không còn host CDN nào.
 */
class TaiNguyenGiaoDienHttpIT extends HttpIT {

    private record TraLoi(int ma, String kieu, String csp) {
    }

    private TraLoi get(String duongDan) {
        return http.get().uri(URI.create("http://localhost:" + port + duongDan))
                .exchange((req, res) -> new TraLoi(res.getStatusCode().value(),
                        res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
                        res.getHeaders().getFirst("Content-Security-Policy")), false);
    }

    @Test
    @DisplayName("module vendor (đường dẫn có @) trả 200 với MIME JavaScript — sai MIME là module không chạy")
    void module_vendor_dung_mime() {
        TraLoi tl = get("/vendor/npm/@codemirror/view@6.34.1/esm.js");

        assertThat(tl.ma()).isEqualTo(200);
        assertThat(tl.kieu()).contains("javascript");
    }

    @Test
    @DisplayName("font KaTeX phục vụ từ chính server — font-src 'self' phải đủ")
    void font_katex_tu_chinh_server() {
        assertThat(get("/vendor/npm/katex@0.16.11/dist/fonts/KaTeX_Main-Regular.woff2").ma())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("★ header CSP thật: script-src chỉ 'self' + Turnstile, không còn jsdelivr")
    void csp_that_khong_mo_cdn() {
        String csp = get("/problem.html").csp();

        assertThat(csp).contains("script-src 'self' https://challenges.cloudflare.com;")
                .doesNotContain("jsdelivr");
    }
}
