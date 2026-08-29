package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Mọi tài nguyên nạp từ máy chủ NGOÀI phải được ghim bằng SRI — Bước 4.12.
 *
 * <h2>Vì sao đây là một luật chứ không phải một lần sửa</h2>
 * Giao diện nạp KaTeX từ jsDelivr, và {@code katex.min.js} chạy với <b>toàn quyền</b> trên
 * trang đang giữ access token trong bộ nhớ. Không có {@code integrity}, một CDN bị xâm nhập —
 * hoặc một phiên bản bị thay lặng lẽ — là mã tuỳ ý chạy trên trang của mọi thí sinh đang thi.
 *
 * <p>Sửa một lần thì lần thêm tài nguyên thứ tư sẽ quên. Luật này bắt <b>lần đó</b>.
 *
 * <h2>Đã có một lần suýt hỏng, và nó hỏng theo hướng ngược lại</h2>
 * Trong lúc viết Bước 4.12, ba hash SRI đã bị <i>gỡ ra</i> vì người viết không kiểm chứng được
 * chúng tại chỗ và không muốn để lại một giá trị có thể sai — một hash sai không bảo vệ gì mà
 * chặn thẳng tài nguyên. Sau khi tải tệp về và tính lại, ba hash ấy hoá ra đúng.
 *
 * <p>Bài học không phải "đừng thận trọng" mà là: <b>giá trị đúng phải kiểm được bằng một lệnh,
 * và sự vắng mặt của nó phải làm test đỏ</b>. Lệnh nằm trong comment của
 * {@code static/problem.html}; vế thứ hai là ca kiểm dưới đây.
 *
 * <h2>Vì sao là unit test chứ không phải IT</h2>
 * Nó chỉ đọc tệp trong {@code src/main/resources/static}. Một luật không cần Postgres thì
 * không nên trả giá một container Postgres — và nó chạy trong vài mili giây, tức là nó chạy
 * ở mọi lần {@code mvnw test}, không chỉ ở {@code verify}.
 */
class TaiNguyenNgoaiTest {

    private static final Path GOC = Path.of("src/main/resources/static");

    /** {@code src=} hoặc {@code href=} trỏ ra một máy chủ khác. Đường dẫn nội bộ bắt đầu bằng "/". */
    private static final Pattern NGOAI =
            Pattern.compile("<(script|link)\\b([^>]*\\b(?:src|href)=\"https?://[^\"]+\"[^>]*)>",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern URL = Pattern.compile("(?:src|href)=\"(https?://[^\"]+)\"");

    private record ThamChieu(String tep, String the, String url, boolean coSri, boolean coCors) {
    }

    private static List<ThamChieu> quet() throws IOException {
        List<ThamChieu> ketQua = new ArrayList<>();
        try (Stream<Path> trang = Files.list(GOC)) {
            for (Path p : trang.filter(f -> f.toString().endsWith(".html")).toList()) {
                String noiDung = Files.readString(p);
                Matcher m = NGOAI.matcher(noiDung);
                while (m.find()) {
                    String the = m.group(1);
                    String thuocTinh = m.group(2);
                    Matcher u = URL.matcher(thuocTinh);
                    String url = u.find() ? u.group(1) : "?";
                    ketQua.add(new ThamChieu(
                            p.getFileName().toString(), the, url,
                            thuocTinh.contains("integrity=\"sha384-")
                                    || thuocTinh.contains("integrity=\"sha512-"),
                            thuocTinh.contains("crossorigin=")));
                }
            }
        }
        return ketQua;
    }

    @Test
    @DisplayName("★ mọi script và stylesheet từ CDN đều có integrity= sha384 hoặc sha512")
    void moi_tai_nguyen_ngoai_deu_duoc_ghim() throws IOException {
        List<ThamChieu> thieu = quet().stream().filter(t -> !t.coSri()).toList();

        assertThat(thieu)
                .describedAs("""
                        Có tài nguyên ngoài KHÔNG được ghim bằng SRI.

                        Nếu bạn vừa thêm một <script> hoặc <link> từ CDN, tính hash rồi dán vào:
                            curl -sL <url> | openssl dgst -sha384 -binary | openssl base64 -A

                        Đừng chép hash từ tài liệu của thư viện — tính từ chính tệp sẽ được nạp.

                        Thiếu: %s""".formatted(thieu))
                .isEmpty();
    }

    @Test
    @DisplayName("★ và có crossorigin= — thiếu nó thì trình duyệt bỏ qua integrity trong im lặng")
    void moi_tai_nguyen_ngoai_deu_co_crossorigin() throws IOException {
        // Đây là cái bẫy: SRI trên tài nguyên khác gốc chỉ có hiệu lực khi phản hồi đi kèm
        // CORS. Thiếu crossorigin= thì trình duyệt KHÔNG báo lỗi, KHÔNG chặn, và cũng KHÔNG
        // kiểm hash — tức là thuộc tính integrity trở thành trang trí.
        List<ThamChieu> thieu = quet().stream().filter(t -> !t.coCors()).toList();

        assertThat(thieu).describedAs("thiếu crossorigin=: %s", thieu).isEmpty();
    }

    @Test
    @DisplayName("phép quét thật sự tìm thấy tài nguyên — luật rỗng là luật sắp bị xoá")
    void phep_quet_khong_chay_rong() throws IOException {
        // Không có ca này thì hai ca trên vẫn xanh sau khi ai đó đổi cấu trúc thư mục và
        // biểu thức tìm kiếm không khớp gì nữa. Một luật luôn xanh là một luật không tồn tại.
        assertThat(quet())
                .describedAs("không quét thấy tài nguyên ngoài nào — biểu thức tìm kiếm hỏng?")
                .isNotEmpty();
    }
}
