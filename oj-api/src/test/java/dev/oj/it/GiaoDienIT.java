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

/**
 * ★ Giao diện — Bước 4.12.
 *
 * <h2>Vì sao một IT cho vài file tĩnh</h2>
 * Vì "trang tĩnh không build step" chỉ đúng nếu Spring <b>thật sự</b> phục vụ chúng, và điều
 * đó phụ thuộc vào một quy ước ({@code classpath:/static/}) chứ không vào mã ta viết. Một
 * dòng cấu hình sai ở đâu đó — một {@code context-path}, một filter bắt {@code /**} — làm cả
 * giao diện biến mất mà không một test Java nào khác đỏ.
 *
 * <p>Ca {@link #moi_tep_duoc_tham_chieu_deu_ton_tai()} bắt kiểu lỗi phổ biến nhất của một dự
 * án không có build step: đổi tên một file rồi quên một thẻ {@code <script src>}. Không có
 * bước đóng gói nào phát hiện hộ, nên phải có một test.
 */
class GiaoDienIT extends HttpIT {

    private static final Path GOC = Path.of("src/main/resources/static");

    private RestClient tho() {
        return RestClient.create("http://localhost:" + port);
    }

    private HttpStatus lay(String duongDan) {
        return (HttpStatus) tho().get().uri(duongDan)
                .exchange((req, res) -> res.getStatusCode(), false);
    }

    /**
     * ★ Quét thư mục, KHÔNG chép tay danh sách trang.
     *
     * <p>Bản đầu liệt kê năm trang bằng tay. Đợt 1 và Đợt 2 thêm bảy trang nữa mà không ai
     * sửa danh sách ấy — nên suốt hai đợt, "trang có phục vụ được không" là câu không ai
     * hỏi. Một danh sách chép tay trong test là một danh sách sẽ lạc hậu, và nó lạc hậu
     * đúng theo hướng làm test dễ xanh hơn.
     */
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

    /**
     * ★ Mỗi {@code list="X"} phải có một {@code <datalist id="X">} thật.
     *
     * <p>Cùng một họ lỗi với ca trên, nhưng im lặng hơn nhiều: một {@code list} trỏ tới id
     * không tồn tại <b>không hỏng gì cả</b>. Trình duyệt chỉ đơn giản không gợi ý, ô nhập
     * vẫn gõ tay được, và không có lỗi nào ở console. Người viết code nghĩ mình đã cho
     * người dùng một danh sách chọn; người dùng thì chưa từng thấy danh sách ấy.
     */
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

        // Frontend dựng ô chọn từ đây thay vì gán cứng ba mã. Nếu ca này đỏ vì ai đó xoá
        // endpoint, giao diện sẽ im lặng mất khả năng nộp bài bằng ngôn ngữ mới bật.
        assertThat(ds).isNotEmpty();
        assertThat(ds.get(0)).containsKeys("code", "displayName", "versionLabel");
        assertThat(ds).extracting(l -> l.get("code")).contains("cpp20");
    }

    @Test
    @DisplayName("★ danh sách ngôn ngữ KHÔNG lộ lệnh biên dịch của worker")
    void khong_lo_lenh_bien_dich() {
        String than = String.valueOf(tho().get().uri("/api/v1/languages")
                .retrieve().body(List.class));

        // Bảng `languages` có cả compile_command và run_command. Chúng là chuyện của worker,
        // và một câu lệnh shell trong response công khai là một bản đồ chỉ đường cho người
        // đang tìm cách thoát sandbox.
        assertThat(than).doesNotContain("g++", "compile", "isolate", "{src}", "{bin}");
    }

    @Test
    @DisplayName("khách chưa đăng nhập vẫn mở được trang đăng nhập")
    void trang_dang_nhap_khong_can_token() {
        assertThat(lay("/login.html")).isEqualTo(HttpStatus.OK);
    }
}
