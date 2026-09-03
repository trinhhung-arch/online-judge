package dev.oj.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Giao diện chỉ được gọi những đường dẫn CÓ THẬT — Bước G1.
 *
 * <h2>Lỗ hổng mà ca này bịt</h2>
 * Giao diện là trang tĩnh, không build step, không type checking ({@code docs/giao-dien-plan.md}
 * mục 1.1). Cái giá của quyết định đó là: một đường dẫn viết sai trong JavaScript
 * <b>không có gì bắt được</b>. Backend đổi tên một endpoint, cả hai bên vẫn "biên dịch", test
 * hai bên vẫn xanh vì mỗi bên tự nói chuyện với chính mình, và triệu chứng duy nhất là một
 * nút không làm gì cả — phát hiện được bằng cách có người bấm thử.
 *
 * <p>Đây là đúng loại lỗi mà {@code HopDongVanHanhTest} sinh ra để chặn ở biên
 * API ↔ worker, và {@code HttpSurfaceTest} chặn ở biên hợp đồng ↔ controller. Biên thứ ba —
 * controller ↔ trình duyệt — cho tới giờ không ai canh.
 *
 * <h2>Hai luật, và luật thứ hai là thứ giữ cho luật thứ nhất có nghĩa</h2>
 * <ol>
 *   <li>Mọi đường dẫn khai trong {@code js/duong-dan.js} phải khớp một
 *       {@code @RequestMapping} có thật.</li>
 *   <li><b>Không file JS nào khác được chứa một đường dẫn API.</b> Thiếu luật này thì luật
 *       một chỉ canh những đường dẫn ai đó nhớ khai vào đúng chỗ — tức là canh những đường
 *       dẫn vốn đã ít sai nhất.</li>
 * </ol>
 *
 * <h2>Vì sao đọc văn bản chứ không chạy JavaScript</h2>
 * Chạy được thì cần Node, và {@code README} nói rõ CI không có Node. Đọc văn bản bắt được
 * đúng thứ cần bắt — một chuỗi đường dẫn — với chi phí bằng không.
 */
class BeMatFrontendTest {

    private static final Path THU_MUC_JS =
            Path.of("src", "main", "resources", "static", "js");

    private static final Path KHAI_BAO = THU_MUC_JS.resolve("duong-dan.js");

    /** {@code '/api/v1/...'}, {@code "/api/v1/..."} hoặc {@code `/api/v1/...`}. */
    private static final Pattern DUONG_DAN =
            Pattern.compile("[\"'`](/api/v1/[^\"'`\\s]*)[\"'`]");

    /** Chỉ để phát hiện "có nhắc tới API" ở luật 2 — lỏng hơn, cố ý. */
    private static final Pattern NHAC_TOI_API = Pattern.compile("/api/v1/");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.oj");

    @Test
    @DisplayName("★ mọi đường dẫn giao diện gọi đều có controller phục vụ")
    void moi_duong_dan_giao_dien_deu_co_that() throws IOException {
        Set<String> khai = docDuongDan(Files.readString(KHAI_BAO));

        assertThat(khai)
                .as("duong-dan.js phải khai ít nhất những endpoint mà bốn trang gốc đã dùng")
                .hasSizeGreaterThanOrEqualTo(8);

        Set<String> coThat = duongDanCuaController();

        List<String> lac = new ArrayList<>();
        for (String d : khai) {
            if (coThat.stream().noneMatch(mau -> khop(mau, d))) {
                lac.add(d);
            }
        }

        assertThat(lac)
                .as("giao diện gọi đường dẫn KHÔNG controller nào phục vụ. Controller hiện có: %s",
                        coThat)
                .isEmpty();
    }

    @Test
    @DisplayName("★ đường dẫn API chỉ sống trong duong-dan.js, không rải ra trang khác")
    void khong_duong_dan_nao_viet_rai() throws IOException {
        List<String> viPham = new ArrayList<>();

        try (Stream<Path> tep = Files.list(THU_MUC_JS)) {
            for (Path f : tep.filter(x -> x.toString().endsWith(".js")).sorted().toList()) {
                if (f.getFileName().equals(KHAI_BAO.getFileName())) {
                    continue;
                }
                String ma = boChuThich(Files.readString(f));
                if (NHAC_TOI_API.matcher(ma).find()) {
                    viPham.add(f.getFileName() + " — đưa đường dẫn ấy vào js/duong-dan.js");
                }
            }
        }

        assertThat(viPham)
                .as("một đường dẫn viết rải là một đường dẫn không ai canh")
                .isEmpty();
    }

    // -------------------------------------------------------------------------

    private static Set<String> docDuongDan(String ma) {
        Set<String> ra = new TreeSet<>();
        Matcher m = DUONG_DAN.matcher(boChuThich(ma));
        while (m.find()) {
            ra.add(chuanHoa(m.group(1)));
        }
        return ra;
    }

    /**
     * Đưa mọi đoạn thay thế về một dấu {@code *}.
     *
     * <p>Phía JS là {@code ${...}} của template literal, phía Java là {@code {id}} của
     * {@code @PathVariable}. Hai cú pháp, một ý nghĩa: "chỗ này là biến".
     */
    private static String chuanHoa(String duong) {
        String d = duong.replaceAll("\\$\\{[^}]*\\}", "*");
        return Stream.of(d.split("/", -1))
                .map(seg -> seg.startsWith("{") && seg.endsWith("}") ? "*" : seg)
                .reduce((a, b) -> a + "/" + b)
                .orElse(d);
    }

    /** Bỏ chú thích khối và chú thích dòng — javadoc ở đây nhắc đường dẫn rất nhiều. */
    private static String boChuThich(String ma) {
        String khongKhoi = ma.replaceAll("(?s)/\\*.*?\\*/", "");
        return Stream.of(khongKhoi.split("\n", -1))
                .filter(dong -> {
                    String t = dong.strip();
                    return !t.startsWith("//") && !t.startsWith("*");
                })
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static Set<String> duongDanCuaController() {
        Set<String> ra = new TreeSet<>();
        for (JavaClass type : CLASSES) {
            if (!type.isAnnotatedWith(RestController.class)
                    || !type.isAnnotatedWith(RequestMapping.class)) {
                continue;
            }
            Class<?> lop = type.reflect();
            String[] goc = lop.getAnnotation(RequestMapping.class).value();
            if (goc.length == 0) {
                continue;
            }
            ra.add(chuanHoa(goc[0]));
            for (java.lang.reflect.Method m : lop.getDeclaredMethods()) {
                for (String duong : duongCuaPhuongThuc(m)) {
                    ra.add(chuanHoa(goc[0] + duong));
                }
            }
        }
        assertThat(ra).as("không nạp được controller nào — bộ nạp class hỏng?").isNotEmpty();
        return ra;
    }

    private static List<String> duongCuaPhuongThuc(java.lang.reflect.Method m) {
        var get = m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        if (get != null) {
            return List.of(get.value());
        }
        var post = m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        if (post != null) {
            return List.of(post.value());
        }
        var patch = m.getAnnotation(org.springframework.web.bind.annotation.PatchMapping.class);
        if (patch != null) {
            return List.of(patch.value());
        }
        var put = m.getAnnotation(org.springframework.web.bind.annotation.PutMapping.class);
        if (put != null) {
            return List.of(put.value());
        }
        return List.of();
    }

    /** {@code *} khớp đúng một đoạn — không khớp xuyên qua dấu gạch chéo. */
    private static boolean khop(String mau, String that) {
        String[] a = mau.split("/", -1);
        String[] b = that.split("/", -1);
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!a[i].equals("*") && !a[i].equals(b[i])) {
                return false;
            }
        }
        return true;
    }
}
