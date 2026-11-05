package dev.oj.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import dev.oj.contract.JudgeEndpoints;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HttpSurfaceTest {

    private static final String PUBLIC_PREFIX = "/api/v1/";
    private static final String INTERNAL_PREFIX = "/internal/";

    private static final String INTERNAL_PACKAGE = ".api.internal";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.oj");

    @Test
    @DisplayName("★ mọi controller công khai mang tiền tố /api/v1 đầy đủ")
    void controller_cong_khai_phai_co_tien_to_day_du() {
        List<String> viPham = new ArrayList<>();
        for (Controller c : controllers()) {
            if (c.internalPackage()) {
                continue;
            }
            if (!c.path().startsWith(PUBLIC_PREFIX)) {
                viPham.add(c.name() + " ánh xạ '" + c.path() + "' — thiếu tiền tố "
                        + PUBLIC_PREFIX + ". KHÔNG dùng server.servlet.context-path để bù: "
                        + "nó kéo cả /internal/** vào /api/v1 và lộ ra tunnel");
            }
        }
        assertThat(viPham).isEmpty();
    }

    @Test
    @DisplayName("★ endpoint nội bộ nằm NGOÀI /api/v1, và chỉ ở package api.internal")
    void endpoint_noi_bo_khong_duoc_lo_ra_tunnel() {
        List<String> viPham = new ArrayList<>();
        for (Controller c : controllers()) {
            if (c.internalPackage() && !c.path().startsWith(INTERNAL_PREFIX)) {
                viPham.add(c.name() + " ở package internal nhưng ánh xạ '" + c.path()
                        + "' — InternalSecretFilter chỉ chặn " + INTERNAL_PREFIX + "*");
            }
            if (!c.internalPackage() && c.path().startsWith(INTERNAL_PREFIX)) {
                viPham.add(c.name() + " ánh xạ '" + c.path() + "' nhưng không nằm trong "
                        + INTERNAL_PACKAGE + " — bề mặt nội bộ phải gom một chỗ");
            }
        }
        assertThat(viPham).isEmpty();
    }

    @Test
    @DisplayName("mọi @RestController đều khai báo @RequestMapping ở cấp class")
    void khong_controller_nao_de_duong_dan_troi_noi() {
        for (JavaClass type : CLASSES.that(new com.tngtech.archunit.base.DescribedPredicate<>(
                "là @RestController") {
            @Override
            public boolean test(JavaClass input) {
                return input.isAnnotatedWith(RestController.class);
            }
        })) {
            assertThat(type.isAnnotatedWith(RequestMapping.class))
                    .as("%s thiếu @RequestMapping ở cấp class — đường dẫn rải trên từng method "
                            + "thì không ai đọc ra được bề mặt HTTP bằng mắt", type.getSimpleName())
                    .isTrue();
        }
    }

    private static List<Controller> controllers() {
        List<Controller> found = new ArrayList<>();
        for (JavaClass type : CLASSES) {
            if (!type.isAnnotatedWith(RestController.class)
                    || !type.isAnnotatedWith(RequestMapping.class)) {
                continue;
            }
            String[] paths = type.reflect().getAnnotation(RequestMapping.class).value();
            for (String path : paths) {
                found.add(new Controller(type.getSimpleName(), path,
                        type.getPackageName().contains(INTERNAL_PACKAGE)));
            }
        }
        assertThat(found).as("không tìm thấy controller nào — bộ nạp class hỏng?").isNotEmpty();
        return found;
    }

    private record Controller(String name, String path, boolean internalPackage) {
    }

    @Test
    @DisplayName("★ mỗi hằng trong JudgeEndpoints đều có controller phục vụ")
    void moi_duong_dan_noi_bo_deu_co_nguoi_phuc_vu() throws Exception {
        Set<String> daKhai = new TreeSet<>();
        for (java.lang.reflect.Field f : JudgeEndpoints.class.getFields()) {
            Object v = f.get(null);
            if (v instanceof String duong && duong.startsWith(JudgeEndpoints.BASE + "/")) {
                daKhai.add(duong);
            }
        }
        assertThat(daKhai).as("hợp đồng phải khai ít nhất bốn đường của M1–M3")
                .hasSizeGreaterThanOrEqualTo(4);

        Set<String> daPhucVu = new TreeSet<>();
        for (JavaClass type : CLASSES) {
            if (!type.isAnnotatedWith(RestController.class)
                    || !type.isAnnotatedWith(RequestMapping.class)) {
                continue;
            }
            Class<?> lop = type.reflect();
            String[] goc = lop.getAnnotation(RequestMapping.class).value();
            if (goc.length == 0 || !goc[0].startsWith(JudgeEndpoints.BASE)) {
                continue;
            }
            if (daKhai.contains(goc[0])) {
                daPhucVu.add(goc[0]);
            }
            for (java.lang.reflect.Method m : lop.getDeclaredMethods()) {
                for (String duong : duongCuaPhuongThuc(m)) {
                    daPhucVu.add(goc[0] + duong);
                }
            }
        }

        assertThat(daPhucVu)
                .as("đường dẫn được hứa trong hợp đồng nhưng KHÔNG controller nào phục vụ")
                .containsAll(daKhai);
    }

    private static List<String> duongCuaPhuongThuc(java.lang.reflect.Method m) {
        var post = m.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class);
        if (post != null) {
            return List.of(post.value());
        }
        var get = m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class);
        if (get != null) {
            return List.of(get.value());
        }
        return List.of();
    }
}
