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

class BeMatFrontendTest {

    private static final Path THU_MUC_JS =
            Path.of("src", "main", "resources", "static", "js");

    private static final Path KHAI_BAO = THU_MUC_JS.resolve("duong-dan.js");

    private static final Pattern DUONG_DAN =
            Pattern.compile("[\"'`](/api/v1/[^\"'`\\s]*)[\"'`]");

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

    @Test
    @DisplayName("★ thứ bậc vai trò trong khung.js trùng đúng enum Role của server")
    void thu_bac_vai_tro_trung_server() throws IOException {
        String ma = Files.readString(THU_MUC_JS.resolve("khung.js"));

        Matcher m = Pattern.compile("THU_BAC\\s*=\\s*\\[([^\\]]*)]").matcher(ma);
        assertThat(m.find()).as("không thấy hằng THU_BAC trong khung.js").isTrue();

        List<String> oGiaoDien = Stream.of(m.group(1).split(","))
                .map(x -> x.trim().replace("'", "").replace("\"", ""))
                .filter(x -> !x.isEmpty())
                .toList();

        List<String> oServer = Stream.of(dev.oj.platform.security.Role.values())
                .map(Enum::name)
                .toList();

        assertThat(oGiaoDien)
                .as("thứ TỰ cũng phải khớp — atLeast so bằng ordinal, không bằng tên")
                .isEqualTo(oServer);
    }

    @Test
    @DisplayName("★ không file JS nào so vai trò bằng === — phải dùng vaiTroItNhat")
    void khong_so_vai_tro_bang_dau_bang() throws IOException {
        List<String> viPham = new ArrayList<>();
        Pattern soBang = Pattern.compile("role\\s*[!=]==");

        try (Stream<Path> tep = Files.list(THU_MUC_JS)) {
            for (Path f : tep.filter(x -> x.toString().endsWith(".js")).sorted().toList()) {
                if (soBang.matcher(boChuThich(Files.readString(f))).find()) {
                    viPham.add(f.getFileName() + " — dùng vaiTroItNhat() thay cho ===");
                }
            }
        }

        assertThat(viPham)
                .as("so bằng làm giao diện chặt hơn server, và không có gì báo khi nó sai")
                .isEmpty();
    }

    private static Set<String> docDuongDan(String ma) {
        Set<String> ra = new TreeSet<>();
        Matcher m = DUONG_DAN.matcher(boChuThich(ma));
        while (m.find()) {
            ra.add(chuanHoa(m.group(1)));
        }
        return ra;
    }

    private static String chuanHoa(String duong) {
        String d = duong.replaceAll("\\$\\{[^}]*\\}", "*");
        return Stream.of(d.split("/", -1))
                .map(seg -> seg.startsWith("{") && seg.endsWith("}") ? "*" : seg)
                .reduce((a, b) -> a + "/" + b)
                .orElse(d);
    }

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
