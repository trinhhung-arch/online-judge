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

class TaiNguyenNgoaiTest {

    private static final Path GOC = Path.of("src/main/resources/static");

    private static final Pattern NGOAI =
            Pattern.compile("<(script|link)\\b([^>]*\\b(?:src|href)=\"(?:https?://|/vendor/)[^\"]+\"[^>]*)>",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern URL = Pattern.compile("(?:src|href)=\"((?:https?://|/vendor/)[^\"]+)\"");

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
        List<ThamChieu> thieu = quet().stream().filter(t -> !t.coCors()).toList();

        assertThat(thieu).describedAs("thiếu crossorigin=: %s", thieu).isEmpty();
    }

    @Test
    @DisplayName("phép quét thật sự tìm thấy tài nguyên — luật rỗng là luật sắp bị xoá")
    void phep_quet_khong_chay_rong() throws IOException {
        assertThat(quet())
                .describedAs("không quét thấy tài nguyên ngoài nào — biểu thức tìm kiếm hỏng?")
                .isNotEmpty();
    }
}
