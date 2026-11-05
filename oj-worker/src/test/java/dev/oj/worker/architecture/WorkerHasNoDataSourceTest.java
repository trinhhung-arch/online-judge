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

class WorkerHasNoDataSourceTest {

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

    private static List<String> artifactIds() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));
        int build = pom.indexOf("<build>");
        String deps = build < 0 ? pom : pom.substring(0, build);

        List<String> ids = new ArrayList<>();
        Matcher m = ARTIFACT_ID.matcher(deps);
        while (m.find()) {
            ids.add(m.group(1).toLowerCase());
        }
        assertThat(ids).as("không đọc được artifactId nào — pom.xml đổi định dạng?").isNotEmpty();
        return ids;
    }

    @Test
    void worker_van_phai_co_duong_HTTP_toi_API() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"));

        assertThat(pom).contains("spring-web");
        assertThat(pom).contains("oj-contract");
    }
}
