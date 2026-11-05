package dev.oj.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class GiaoDienIT extends HttpIT {

    private static final Path GOC = Path.of("src/main/resources/static");

    private RestClient tho() {
        return RestClient.create("http://localhost:" + port);
    }

    private HttpStatus lay(String duongDan) {
        return (HttpStatus) tho().get().uri(duongDan)
                .exchange((req, res) -> res.getStatusCode(), false);
    }

    @Test
    @DisplayName("★ Spring phục vụ MỌI trang tĩnh có trong thư mục")
    void trang_tinh_duoc_phuc_vu() throws Exception {
        List<String> canPhucVu = new java.util.ArrayList<>(List.of(
                "/", "/css/app.css", "/js/api.js", "/js/khung.js", "/js/sse.js",
                "/js/editor.js", "/js/nhap.js"));

        try (var trang = Files.list(GOC)) {
            trang.filter(f -> f.toString().endsWith(".html"))
                    .map(f -> "/" + f.getFileName())
                    .sorted()
                    .forEach(canPhucVu::add);
        }

        assertThat(canPhucVu)
                .as("không quét thấy trang HTML nào — GOC trỏ sai chỗ?")
                .hasSizeGreaterThan(10);

        for (String duongDan : canPhucVu) {
            assertThat(lay(duongDan))
                    .describedAs("Spring không phục vụ %s — kiểm classpath:/static/", duongDan)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("★ mọi tệp được HTML tham chiếu đều tồn tại")
    void moi_tep_duoc_tham_chieu_deu_ton_tai() throws Exception {
        Pattern noiBo = Pattern.compile("(?:src|href)=\"(/[^\"]+)\"");

        try (var trang = Files.list(GOC)) {
            for (Path p : trang.filter(f -> f.toString().endsWith(".html")).toList()) {
                Matcher m = noiBo.matcher(Files.readString(p));
                while (m.find()) {
                    String tep = m.group(1);
                    assertThat(GOC.resolve(tep.substring(1)))
                            .describedAs("%s trỏ tới %s nhưng tệp đó không tồn tại",
                                    p.getFileName(), tep)
                            .exists();
                }
            }
        }
    }

    @Test
    @DisplayName("★ mọi list= đều trỏ tới một <datalist> có thật")
    void moi_datalist_deu_ton_tai() throws Exception {
        Pattern thamChieu = Pattern.compile("\\blist=\"([^\"]+)\"");

        try (var trang = Files.list(GOC)) {
            for (Path p : trang.filter(f -> f.toString().endsWith(".html")).toList()) {
                String html = Files.readString(p);
                Matcher m = thamChieu.matcher(html);
                while (m.find()) {
                    assertThat(html)
                            .describedAs("%s có list=\"%s\" nhưng không có <datalist> nào "
                                    + "mang id đó — gợi ý sẽ im lặng biến mất",
                                    p.getFileName(), m.group(1))
                            .contains("<datalist id=\"" + m.group(1) + "\"");
                }
            }
        }
    }

    @Test
    @DisplayName("★ NFR M4 — thêm một ngôn ngữ chấm là 1 dòng config, 0 dòng code")
    void danh_sach_ngon_ngu_den_tu_database() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ds = tho().get().uri("/api/v1/languages")
                .retrieve().body(List.class);

        assertThat(ds).isNotEmpty();
        assertThat(ds.get(0)).containsKeys("code", "displayName", "versionLabel");
        assertThat(ds).extracting(l -> l.get("code")).contains("cpp20");
    }

    @Test
    @DisplayName("★ danh sách ngôn ngữ KHÔNG lộ lệnh biên dịch của worker")
    void khong_lo_lenh_bien_dich() {
        String than = String.valueOf(tho().get().uri("/api/v1/languages")
                .retrieve().body(List.class));

        assertThat(than).doesNotContain("g++", "compile", "isolate", "{src}", "{bin}");
    }

    @Test
    @DisplayName("khách chưa đăng nhập vẫn mở được trang đăng nhập")
    void trang_dang_nhap_khong_can_token() {
        assertThat(lay("/login.html")).isEqualTo(HttpStatus.OK);
    }
}
