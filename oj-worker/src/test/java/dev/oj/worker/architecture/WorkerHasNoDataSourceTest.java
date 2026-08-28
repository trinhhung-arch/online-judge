package dev.oj.worker.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bất biến #3 — <b>worker không có {@code DataSource}</b> — ép bằng CI, không bằng lời hứa.
 *
 * <p>{@code docs/build-order.md} Bước M1-9 yêu cầu đúng test này: đọc {@code pom.xml} và fail
 * nếu thấy một trong những cái tên dưới đây.
 *
 * <h2>Vì sao kiểm ở tầng {@code pom.xml} chứ không phải tầng import</h2>
 * Vì đây là chỗ vi phạm <i>bắt đầu</i>. Một dòng {@code import java.sql.Connection} chỉ viết
 * được sau khi đã có ai đó thêm driver vào pom; chặn ở pom là chặn trước một bước, và sửa
 * pom là việc phải hỏi người (CLAUDE.md mục 5.2). Test này biến quy tắc đó thành một cái
 * chuông thay vì một câu trong tài liệu.
 *
 * <p>Worker có DB là worker không scale ngang được (S1, S2) và không đổi transport được:
 * cả đường chuyển Postgres → RabbitMQ ở M6 chỉ rẻ vì worker chưa bao giờ biết DB tồn tại.
 */
class WorkerHasNoDataSourceTest {

    /** Mỗi tên ở đây là một cách khác nhau để worker chạm tới hạ tầng của API. */
    private static final List<String> BI_CAM = List.of(
            "spring-boot-starter-data-jdbc",
            "spring-boot-starter-jdbc",
            "spring-boot-starter-data-jpa",
            "postgresql",
            "flyway",
            "lettuce",
            "spring-boot-starter-data-redis",
            "minio",
            "hikari");

    private static final Pattern ARTIFACT_ID =
            Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");

    @Test
    @DisplayName("oj-worker/pom.xml không được chứa bất kỳ dependency hạ tầng nào")
    void pom_khong_co_dependency_ha_tang() throws IOException {
        for (String artifact : artifactIds()) {
            for (String cam : BI_CAM) {
                assertThat(artifact)
                        .as("bất biến #3: oj-worker không được phụ thuộc '%s'. "
                                + "Nếu nhiệm vụ có vẻ cần worker đọc DB thì dữ liệu đó phải nằm "
                                + "trong oj-contract — dừng lại và hỏi (CLAUDE.md mục 5.3)", cam)
                        .doesNotContain(cam);
            }
        }
    }

    /**
     * Đọc <b>giá trị</b> của các thẻ {@code <artifactId>}, không phải văn bản thô của file.
     *
     * <p>Quét thô thì chính đoạn chú thích trong {@code pom.xml} — đoạn liệt kê những thứ bị
     * cấm để người đọc biết — sẽ làm test đỏ. Một test đỏ vì lời giải thích về chính nó là
     * loại test sẽ bị xoá thay vì được sửa.
     */
    private static List<String> artifactIds() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        int build = pom.indexOf("<build>");     // plugin không phải dependency
        String deps = build < 0 ? pom : pom.substring(0, build);

        List<String> ids = new ArrayList<>();
        Matcher m = ARTIFACT_ID.matcher(deps);
        while (m.find()) {
            ids.add(m.group(1).toLowerCase());
        }
        assertThat(ids).as("không đọc được artifactId nào — pom.xml đổi định dạng?").isNotEmpty();
        return ids;
    }

    /** Mặt còn lại: worker chỉ nói chuyện với API qua HTTP, nên nó cần đúng một client. */
    @Test
    void worker_van_phai_co_duong_HTTP_toi_API() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("spring-web");
        assertThat(pom).contains("oj-contract");
    }
}
