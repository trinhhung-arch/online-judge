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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bề mặt HTTP: <b>đúng hai tiền tố, và chỉ hai</b>.
 *
 * <pre>
 *   /api/v1/**       công khai — Cloudflare Tunnel publish tiền tố NÀY và chỉ tiền tố này
 *   /internal/**     nội bộ    — InternalSecretFilter chặn, tunnel KHÔNG publish
 * </pre>
 *
 * <h2>Vì sao test này tồn tại</h2>
 * Vì lỗi nó bắt đã xảy ra thật: {@code ProblemController} từng mang
 * {@code @RequestMapping("/problems")} — thiếu tiền tố {@code /api/v1} — và không có gì báo.
 * Ứng dụng khởi động bình thường, endpoint hoạt động bình thường, chỉ có điều nó nằm ngoài
 * tập đường dẫn mà tunnel publish. Loại lỗi đó không hiện ra cho tới lúc có người bên ngoài
 * thử mở trang.
 *
 * <p>Chiều ngược lại còn nguy hơn: một controller nội bộ vô tình nằm dưới {@code /api/v1} sẽ
 * <b>được tunnel publish ra internet</b>, và bất kỳ ai gọi được nó thì ghi được verdict cho
 * mọi bài nộp. Đó là lý do luật thứ hai ở đây kiểm cả hai chiều.
 */
class HttpSurfaceTest {

    private static final String PUBLIC_PREFIX = "/api/v1/";
    private static final String INTERNAL_PREFIX = "/internal/";

    /** Package chứa endpoint nội bộ — {@code grep -r internal} phải ra đúng chỗ này. */
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
        // Nếu con số này về 0 thì test xanh vô nghĩa — đúng cái bẫy mà
        // archRule.failOnEmptyShould=false tạo ra cho ArchitectureTest.
        assertThat(found).as("không tìm thấy controller nào — bộ nạp class hỏng?").isNotEmpty();
        return found;
    }

    private record Controller(String name, String path, boolean internalPackage) {
    }
}
