package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class HopDongTrienKhaiTest {

    private static final Path YML_WORKER =
            Path.of("..", "oj-worker", "src", "main", "resources", "application.yml");

    private static final Path YML_API =
            Path.of("src", "main", "resources", "application.yml");

    private static final Path SEED = Path.of("src", "main", "resources", "db", "migration",
            "R__seed_du_lieu_tham_chieu.sql");

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");

    private static final Path YML_DEV =
            Path.of("src", "main", "resources", "application-dev.yml");

    private static final Path POM = Path.of("pom.xml");

    @Test
    @DisplayName("★ worker không đặt tên KHÔNG được mặc định thành máy chấm chuẩn")
    void worker_khong_duoc_mac_dinh_thanh_may_cham_chuan() throws IOException {
        String macDinh = macDinhCuaEnv(chuoi(Files.readString(YML_WORKER), "host-name"));
        String mayChuan = mayChamChuanTrongSeed(Files.readString(SEED));

        assertThat(macDinh)
                .as("oj.worker.host-name mặc định là '%s', đúng bằng máy chấm chuẩn trong "
                        + "R__seed. Một worker quên đặt OJ_WORKER_HOST_NAME sẽ tự xưng là máy "
                        + "chuẩn, và mayChamSong (đếm dòng judge_hosts) không còn phát hiện "
                        + "được một máy chấm đã chết. Đặt mặc định thành một tên KHÔNG có "
                        + "trong judge_hosts", macDinh)
                .isNotEqualTo(mayChuan);
    }

    private static String macDinhCuaEnv(String v) {
        Matcher m = Pattern.compile("^\\$\\{[A-Z_]+:(.*)}$").matcher(v);
        return m.find() ? macDinhCuaEnv(m.group(1)) : v;
    }

    private static String mayChamChuanTrongSeed(String seed) {
        Matcher m = Pattern.compile("VALUES \\('([^']+)',\\s*'[^']+',\\s*\\d+,\\s*[\\d.]+,\\s*TRUE\\)")
                .matcher(seed);
        assertThat(m.find()).as("không thấy dòng judge_hosts nào có is_reference = TRUE trong seed")
                .isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("★ healthcheck RabbitMQ không được gọi CLI dựng node Erlang")
    void healthcheck_rabbit_khong_dung_cli_erlang() throws IOException {
        String lenh = lenhHealthcheckRabbit(Files.readString(COMPOSE));

        assertThat(lenh)
                .as("healthcheck RabbitMQ đang là `%s`. Mỗi lần chạy nó dựng một node Erlang "
                        + "phân tán trong dải 11 cổng 35672–35682 và để lại tiến trình treo; "
                        + "15 giây một lần thì dải ấy cạn và healthcheck đỏ vĩnh viễn. Dùng "
                        + "thứ không dựng VM — `nc -z localhost 5672` đã đo là phân biệt được "
                        + "cổng mở/đóng", lenh)
                .doesNotContain("rabbitmq-diagnostics")
                .doesNotContain("rabbitmqctl");
    }

    private static String lenhHealthcheckRabbit(String compose) {
        int i = compose.indexOf("  rabbitmq:");
        assertThat(i).as("không thấy service rabbitmq trong %s", COMPOSE).isNotNegative();
        Matcher m = Pattern.compile("(?m)^\s*test:\s*(.+)$").matcher(compose.substring(i));
        assertThat(m.find()).as("service rabbitmq không có healthcheck nào").isTrue();
        return m.group(1).trim();
    }

    @Test
    @DisplayName("★ kết nối của flyway-maven-plugin khớp mặc định của spring.flyway")
    void flyway_plugin_noi_dung_database() throws IOException {
        String pom = Files.readString(POM);
        String yml = Files.readString(YML_API);

        assertThat(theThePom(pom, "flyway.url"))
                .as("pom.xml và application.yml trỏ tới hai database khác nhau — "
                        + "flyway:repair sẽ sửa nhầm chỗ, thành công, và im lặng")
                .isEqualTo(macDinhCuaEnv(chuoiSauKhoa(yml, "url")));
        assertThat(theThePom(pom, "flyway.user"))
                .isEqualTo(macDinhCuaEnv(chuoiSauKhoa(yml, "user")));
        assertThat(theThePom(pom, "flyway.password"))
                .isEqualTo(macDinhCuaEnv(chuoiSauKhoa(yml, "password")));
    }

    @Test
    @DisplayName("★ flyway:clean phải bị tắt — nó xoá sạch database")
    void flyway_clean_phai_tat() throws IOException {
        assertThat(Files.readString(POM))
                .as("thiếu <cleanDisabled>true</cleanDisabled> thì `mvn flyway:clean` gõ nhầm "
                        + "một lần là mất toàn bộ bài nộp — lời hứa số hai của hệ thống này")
                .contains("<cleanDisabled>true</cleanDisabled>");
    }

    @Test
    @DisplayName("★ flyway.locations của plugin phủ mọi thư mục profile dev nạp")
    void flyway_plugin_nhin_thay_moi_migration() throws IOException {
        String pom = Files.readString(POM);
        String ymlDev = Files.readString(YML_DEV);

        String cuaPlugin = theThePom(pom, "flyway.locations");

        assertThat(cuaPlugin)
                .as("flyway.locations phải neo bằng ${project.basedir}. Flyway giải "
                        + "`filesystem:` theo thư mục gõ lệnh `mvn` — thường là gốc repo — "
                        + "chứ không theo thư mục module. Đường dẫn tương đối trỏ vào chỗ "
                        + "trống, và repair tombstone SẠCH mọi repeatable migration")
                .contains("${project.basedir}");
        for (String thuMuc : thuMucTrongLocations(chuoi(ymlDev, "locations"))) {
            assertThat(cuaPlugin)
                    .as("application-dev.yml nạp '%s' nhưng flyway.locations của plugin thì "
                            + "không. `flyway:repair` sẽ coi mọi migration trong đó là đã bị "
                            + "xoá và ghi tombstone — lần khởi động sau chúng chạy lại từ đầu",
                            thuMuc)
                    .contains(thuMuc);
        }
    }

    private static java.util.List<String> thuMucTrongLocations(String locations) {
        java.util.List<String> ra = new java.util.ArrayList<>();
        for (String phan : locations.split(",")) {
            ra.add(phan.trim().replaceFirst("^(classpath|filesystem):", ""));
        }
        assertThat(ra).as("không bóc được thư mục nào từ '%s'", locations).isNotEmpty();
        return ra;
    }

    private static String theThePom(String pom, String ten) {
        Matcher m = Pattern.compile("<" + Pattern.quote(ten) + ">(.*?)</"
                + Pattern.quote(ten) + ">").matcher(pom);
        assertThat(m.find()).as("không thấy thẻ <%s> trong %s", ten, POM).isTrue();
        return m.group(1).trim();
    }

    private static String chuoiSauKhoa(String yml, String khoa) {
        int i = yml.indexOf("  flyway:");
        assertThat(i).as("không thấy khối spring.flyway trong %s", YML_API).isNotNegative();
        Matcher m = Pattern.compile("(?m)^\\s{4}" + Pattern.quote(khoa) + ":\\s*(\\S+)")
                .matcher(yml.substring(i));
        assertThat(m.find()).as("không thấy khoá '%s' trong khối spring.flyway", khoa).isTrue();
        return m.group(1);
    }

    private static String chuoi(String yml, String khoa) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(khoa) + ":\\s*(\\S+)").matcher(yml);
        assertThat(m.find()).as("không thấy khoá '%s'", khoa).isTrue();
        return m.group(1);
    }
}
