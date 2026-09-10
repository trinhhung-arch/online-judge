package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ràng buộc giữa các file <b>cấu hình triển khai</b>: {@code docker-compose.yml},
 * {@code pom.xml}, {@code R__seed} và {@code application.yml} của worker.
 *
 * <h2>Vì sao tách khỏi {@code HopDongVanHanhTest}</h2>
 * Lớp kia canh ba con số và hai cái tên giữa <i>hai file yml của hai module</i> — một chủ đề
 * hẹp và rõ. Ba ca ở đây khác chất: chúng canh những thứ chỉ hỏng khi hệ thống được
 * <b>dựng và chạy thật</b>, và mỗi ca ra đời từ một sự cố có thật ngày 2026-09-10. Nhét chung
 * thì file vượt trần 300 dòng ({@code CLAUDE.md} mục 7), và quan trọng hơn là hai chủ đề khác
 * nhau nằm chung một cái tên.
 *
 * <h2>Điểm chung của cả ba: không ca nào bắt được bằng {@code @SpringBootTest}</h2>
 * Chúng sống ở tầng file — compose, pom, SQL seed — nơi trình biên dịch im lặng và Spring
 * không nhìn tới. Cách duy nhất để chúng đỏ trước khi tới máy thật là đọc thẳng văn bản.
 */
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

    /**
     * ★ Ca này canh một lỗi <b>đã chạy im lặng sáu ngày</b> trên máy dev, không phải một lo xa.
     *
     * <h2>Triệu chứng, và vì sao không ai thấy</h2>
     * {@code oj.worker.host-name} từng mặc định đúng bằng tên máy chấm chuẩn. Nên một worker
     * thứ hai khởi động bằng {@code spring-boot:run} trần — không đặt biến nào — <i>sinh ra đã
     * mang tên máy chuẩn</i>. Không bước nào sai, không log nào đỏ, và hai chỉ số hỏng cùng lúc:
     * <ul>
     *   <li>{@code mayChamSong} đếm <b>dòng</b> {@code judge_hosts} ({@code JdbcQueueStatusQuery})
     *       nên hai worker vẫn ra 1 — và tắt một cái đi thì vẫn là 1, vì cái còn lại vẫn touch
     *       chung một dòng. Chỉ số "máy chấm còn sống không" mất hẳn khả năng nói "không".</li>
     *   <li>{@code judge_runs.host_id} tra theo tên, nên không lần chấm nào truy được về đúng
     *       máy đã chấm — và {@code judge_hosts.host_factor} bị worker chưa hiệu chuẩn ghi đè
     *       mỗi 15 phút.</li>
     * </ul>
     *
     * <h2>Vì sao là ca kiểm chứ không phải một dòng bình luận</h2>
     * Cái giá trị mặc định ấy trông hoàn toàn hợp lý khi đọc file — nó đúng tên máy đang chạy
     * thật. Chỉ khi có worker <i>thứ hai</i> nó mới thành sai, mà worker thứ hai thì không xuất
     * hiện trong bất kỳ file cấu hình nào để ai đó đọc ra. Chỉ có một ràng buộc kiểm được mới
     * bắt được lần sau.
     */
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


    /**
     * Mặc định TRONG CÙNG của {@code ${A:${B:mac-dinh}}}. Bóc lồng nhau vì
     * {@code spring.flyway.user} có đúng dạng hai lớp ấy.
     */
    private static String macDinhCuaEnv(String v) {
        Matcher m = Pattern.compile("^\\$\\{[A-Z_]+:(.*)}$").matcher(v);
        return m.find() ? macDinhCuaEnv(m.group(1)) : v;
    }

    /** Tên ở dòng {@code judge_hosts} duy nhất có {@code is_reference = TRUE}. */
    private static String mayChamChuanTrongSeed(String seed) {
        Matcher m = Pattern.compile("VALUES \\('([^']+)',\\s*'[^']+',\\s*\\d+,\\s*[\\d.]+,\\s*TRUE\\)")
                .matcher(seed);
        assertThat(m.find()).as("không thấy dòng judge_hosts nào có is_reference = TRUE trong seed")
                .isTrue();
        return m.group(1);
    }

    /**
     * ★ Healthcheck của RabbitMQ <b>không được dựng một máy ảo Erlang</b>.
     *
     * <h2>Ca này canh một sự cố đã ăn 11 GiB, và một bản vá đã KHÔNG đủ</h2>
     * {@code rabbitmq-diagnostics} (và {@code rabbitmqctl}) dựng một node Erlang phân tán
     * trong dải cổng chỉ có 11 chỗ do chính CLI đặt. Chạy nó 15 giây một lần trong một
     * healthcheck sinh ra hai tầng hỏng, cả hai đo thật ngày 2026-09-10:
     * <ul>
     *   <li><b>Chí mạng:</b> lần ping bị Docker SIGKILL để lại tiến trình beam giữ một cổng
     *       trong dải ấy. Đủ 11 lần là dải cạn và MỌI lần ping sau đều hỏng — 460 tiến trình
     *       treo, 11,2 GiB, {@code FailingStreak} 4118, trong khi broker vẫn phục vụ bình
     *       thường suốt năm ngày.</li>
     *   <li><b>Âm ỉ:</b> thêm {@code --timeout 5} chặn được tầng trên — epmd sạch, dải cổng
     *       không còn bị giữ. Nhưng đo lại sau hai giờ vẫn có 17 beam mồ côi, và lần này
     *       <i>ping đang thành công</i>. Bản vá ấy thu hẹp thiệt hại chứ không đóng được lỗ.</li>
     * </ul>
     *
     * <h2>Vì sao ràng buộc là "cấm CLI" chứ không phải "phải có --timeout"</h2>
     * Bản đầu của ca này ép {@code --timeout} nhỏ hơn {@code timeout} của Docker — tức nó
     * canh đúng <b>bản vá đã tỏ ra không đủ</b>. Một ca kiểm canh một bản vá sai sẽ xanh mãi
     * trong lúc lỗi vẫn chảy. Thứ đáng ép là tính chất gốc: đừng chạy một runtime nặng, có
     * tài nguyên toàn cục hữu hạn, bốn nghìn lần một ngày, chỉ để hỏi "còn sống không".
     */
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

    /** Dòng {@code test:} của khối healthcheck trong service {@code rabbitmq}. */
    private static String lenhHealthcheckRabbit(String compose) {
        int i = compose.indexOf("  rabbitmq:");
        assertThat(i).as("không thấy service rabbitmq trong %s", COMPOSE).isNotNegative();
        Matcher m = Pattern.compile("(?m)^\s*test:\s*(.+)$").matcher(compose.substring(i));
        assertThat(m.find()).as("service rabbitmq không có healthcheck nào").isTrue();
        return m.group(1).trim();
    }

    /**
     * ★ Ba giá trị kết nối của {@code flyway-maven-plugin} phải khớp mặc định của
     * {@code spring.flyway.*}.
     *
     * <h2>Hỏng thì hỏng thế nào</h2>
     * {@code ./mvnw -pl oj-api flyway:repair} sửa bảng {@code flyway_schema_history} của
     * <b>database mà plugin nối tới</b>. Lệch một chữ trong URL là nó làm đúng thao tác ấy
     * trên một database khác: báo thành công, không cảnh báo gì, và cái đang hỏng thì vẫn
     * hỏng. Người dùng sẽ chạy lại lần nữa, rồi đi tìm lỗi ở chỗ khác.
     *
     * <h2>Vì sao hai chỗ không tự bám theo nhau được</h2>
     * Maven không có cú pháp {@code ${BIEN:mac-dinh}} như Spring, nên giá trị phải viết lại
     * trong {@code pom.xml}. Ca này giữ phần <b>mặc định</b> — phần dùng trên mọi máy dev.
     * Nó KHÔNG giữ được trường hợp ai đó đặt {@code OJ_DB_URL} cho app rồi quên truyền
     * {@code -Dflyway.url}; chú thích trong {@code pom.xml} nói về ca ấy.
     */
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

    /**
     * ★ {@code flyway:clean} phải tắt.
     *
     * <p>Nó XOÁ TOÀN BỘ schema — mọi bảng, mọi bài nộp, mọi verdict. Thêm plugin này cũng là
     * thêm một lệnh một dòng làm được điều đó, và {@code clean} chỉ cách {@code repair} vài
     * phím. Không có bước xác nhận nào chen vào giữa.
     */
    @Test
    @DisplayName("★ flyway:clean phải bị tắt — nó xoá sạch database")
    void flyway_clean_phai_tat() throws IOException {
        assertThat(Files.readString(POM))
                .as("thiếu <cleanDisabled>true</cleanDisabled> thì `mvn flyway:clean` gõ nhầm "
                        + "một lần là mất toàn bộ bài nộp — lời hứa số hai của hệ thống này")
                .contains("<cleanDisabled>true</cleanDisabled>");
    }


    /**
     * ★ {@code flyway.locations} của plugin phải phủ MỌI thư mục ứng dụng nạp.
     *
     * <h2>Thiếu một thư mục thì `repair` GÂY RA hỏng, chứ không sửa hỏng</h2>
     * `repair` xoá khỏi lịch sử mọi migration nó <b>không nhìn thấy file</b> — nó cho rằng
     * file đã bị xoá khỏi dự án. Cho nó nhìn thiếu là cho nó xoá nhầm.
     *
     * <p>Đã xảy ra 2026-09-10: plugin để mặc định (chỉ {@code db/migration}), nên nó không
     * thấy {@code db/dev-seed} mà profile {@code dev} có nạp. Một lượt {@code flyway:repair}
     * ghi tombstone {@code type=DELETE} cho {@code R__seed_du_lieu_dev}, Flyway từ đó coi seed
     * ấy là chưa từng chạy, chạy lại từ đầu ở lần khởi động kế tiếp, đâm vào
     * {@code ck_users_anonymized}, và API không lên được.
     *
     * <p>Ca này so với {@code application-dev.yml} vì đó là profile có NHIỀU thư mục nhất —
     * phủ được nó là phủ được mọi profile hẹp hơn.
     */
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

    /** {@code classpath:db/migration,classpath:db/dev-seed} → {@code [db/migration, db/dev-seed]}. */
    private static java.util.List<String> thuMucTrongLocations(String locations) {
        java.util.List<String> ra = new java.util.ArrayList<>();
        for (String phan : locations.split(",")) {
            ra.add(phan.trim().replaceFirst("^(classpath|filesystem):", ""));
        }
        assertThat(ra).as("không bóc được thư mục nào từ '%s'", locations).isNotEmpty();
        return ra;
    }

    /** Giá trị của một thẻ {@code <ten>...</ten>} trong pom. */
    private static String theThePom(String pom, String ten) {
        Matcher m = Pattern.compile("<" + Pattern.quote(ten) + ">(.*?)</"
                + Pattern.quote(ten) + ">").matcher(pom);
        assertThat(m.find()).as("không thấy thẻ <%s> trong %s", ten, POM).isTrue();
        return m.group(1).trim();
    }

    /** Giá trị thô sau {@code khoa:} trong khối {@code spring.flyway} của application.yml. */
    private static String chuoiSauKhoa(String yml, String khoa) {
        int i = yml.indexOf("  flyway:");
        assertThat(i).as("không thấy khối spring.flyway trong %s", YML_API).isNotNegative();
        Matcher m = Pattern.compile("(?m)^\\s{4}" + Pattern.quote(khoa) + ":\\s*(\\S+)")
                .matcher(yml.substring(i));
        assertThat(m.find()).as("không thấy khoá '%s' trong khối spring.flyway", khoa).isTrue();
        return m.group(1);
    }

    /**
     * Giá trị đầu tiên sau {@code khoa:} ở bất kỳ độ thụt nào. Dùng cho những khoá duy nhất
     * trong file của chúng; khoá trùng tên ở nhiều khối thì phải neo, xem {@link #chuoiSauKhoa}.
     */
    private static String chuoi(String yml, String khoa) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(khoa) + ":\\s*(\\S+)").matcher(yml);
        assertThat(m.find()).as("không thấy khoá '%s'", khoa).isTrue();
        return m.group(1);
    }
}
