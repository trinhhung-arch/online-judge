package dev.oj.architecture;

import dev.oj.judging.infrastructure.RabbitJudgeJobPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Ba con số và hai cái tên sống ở <b>hai file cấu hình khác nhau</b> và phải khớp nhau.
 * Bước 6.3, 6.4 và 6.8.
 *
 * <h2>Vì sao chúng không nằm trong {@code oj-contract}, nơi đúng ra chúng thuộc về</h2>
 * {@code oj-contract} là hợp đồng đã đóng băng giữa hai người, và đổi nó là việc phải hỏi
 * người ({@code CLAUDE.md} mục 5.1). Bước 6.4 được duyệt với điều kiện <i>không đổi một dòng
 * nào</i> ở đó — nên tên hàng đợi phải lặp lại ở {@code oj-worker/application.yml}.
 *
 * <p>Đó chính xác là loại trùng lặp mà javadoc của {@code JudgeEndpoints} nói sinh ra lỗi tệ
 * nhất: <i>"trình biên dịch im lặng, test hai bên vẫn xanh vì mỗi bên dùng hằng của chính
 * mình, và triệu chứng duy nhất là mọi thứ ngừng hoạt động"</i>. File này là cái giá phải trả
 * cho việc giữ hợp đồng đóng băng — và nó rẻ hơn nhiều so với một buổi tối đi tìm lý do worker
 * không nhận việc.
 *
 * <h2>Đọc file, không đọc cấu hình đã nạp</h2>
 * Một {@code @SpringBootTest} chỉ nạp {@code application.yml} của <i>một</i> module. Hai file
 * ở hai module chỉ so sánh được bằng cách đọc thẳng văn bản — cùng kỹ thuật
 * {@code WorkerHasNoDataSourceTest} đã dùng để đọc {@code pom.xml}.
 */
class HopDongVanHanhTest {

    private static final Path YML_WORKER =
            Path.of("..", "oj-worker", "src", "main", "resources", "application.yml");

    private static final Path YML_API =
            Path.of("src", "main", "resources", "application.yml");

    private static final Path SEED = Path.of("src", "main", "resources", "db", "migration",
            "R__seed_du_lieu_tham_chieu.sql");

    @Test
    @DisplayName("★ tên hai hàng đợi ở oj-worker khớp hằng số của oj-api")
    void ten_hang_doi_khop() throws IOException {
        String worker = Files.readString(YML_WORKER);

        assertThat(chuoi(worker, "live-queue"))
                .as("oj.worker.rabbit.live-queue phải bằng RabbitJudgeJobPublisher.HANG_LIVE")
                .isEqualTo(RabbitJudgeJobPublisher.HANG_LIVE);
        assertThat(chuoi(worker, "rejudge-queue"))
                .as("oj.worker.rabbit.rejudge-queue phải bằng RabbitJudgeJobPublisher.HANG_REJUDGE")
                .isEqualTo(RabbitJudgeJobPublisher.HANG_REJUDGE);
    }

    /**
     * ★ Bước 6.3 — {@code max-in-flight} <b>là</b> cách viết "trần 30% năng lực chấm" thành
     * một con số kiểm được.
     *
     * <p>Mỗi dòng rejudge đang chờ là nhiều nhất một judge slot có thể bận vì nó. Nên trần
     * đúng là {@code round(slots × 0.30)}, và nó phụ thuộc một con số nằm ở file của module
     * kia. Đổi {@code OJ_WORKER_SLOTS} mà quên đổi trần thì FR-ADM-01 im lặng sai — 2 trên 3
     * slot là 67%, không phải 30%, và triệu chứng là thí sinh chờ lâu trong lúc một job rejudge
     * chạy.
     */
    @Test
    @DisplayName("★ trần rejudge vẫn là 30% số judge slot của worker")
    void tran_rejudge_van_la_30_phan_tram() throws IOException {
        int slots = so(Files.readString(YML_WORKER), "slots");
        int tran = so(Files.readString(YML_API), "max-in-flight");

        int chophep = Math.max(1, Math.round(slots * 0.30f));
        assertThat(tran)
                .as("oj.judge.rejudge.max-in-flight (%d) phải = round(%d slot × 30%%) = %d. "
                        + "Đổi OJ_WORKER_SLOTS thì phải đổi cả hai chỗ.", tran, slots, chophep)
                .isEqualTo(chophep);
    }

    /**
     * Bước 6.8 — ân hạn tắt máy phải nằm giữa "một bài chấm xong" và "lease hết hạn".
     *
     * <p>Ngắn quá thì {@code stop()} cắt ngang bài đang chấm, đúng thứ bước này sinh ra để
     * sửa. Dài hơn lease thì reaper đã giao bài cho worker khác trong lúc ta còn đang chờ —
     * và lúc đó ta chờ để hoàn thành một việc chắc chắn sẽ bị khoá lạc quan từ chối.
     */
    @Test
    @DisplayName("shutdown-grace nằm giữa thời gian chấm một bài và lease 120s")
    void an_han_tat_may_hop_ly() throws IOException {
        String worker = Files.readString(YML_WORKER);
        Duration anHan = thoiGian(worker, "shutdown-grace");
        Duration lease = thoiGian(worker, "lease");

        assertThat(anHan).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(anHan).isLessThan(lease);
    }

    /**
     * Bước 6.7 — cửa sổ liveness phải ≥ 2 nhịp benchmark.
     *
     * <p>{@code judge_hosts.last_seen_at} chỉ được cập nhật bởi endpoint {@code benchmark}.
     * Đặt cửa sổ nhỏ hơn hai chu kỳ là báo động giả đều đặn: mất đúng một nhịp benchmark —
     * chuyện thường khi máy bận — thì mọi worker bị coi là chết.
     */
    @Test
    @DisplayName("host-liveness ≥ 2 lần nhịp benchmark của worker")
    void cua_so_liveness_du_rong() throws IOException {
        Duration nhip = thoiGian(Files.readString(YML_WORKER), "interval");
        Duration cuaSo = thoiGian(Files.readString(YML_API), "host-liveness");

        assertThat(cuaSo)
                .as("oj.judge.host-liveness (%s) phải ≥ 2 × oj.worker.sandbox.benchmark"
                        + ".interval (%s)", cuaSo, nhip)
                .isGreaterThanOrEqualTo(nhip.multipliedBy(2));
    }

    /**
     * ★ Số luồng {@code @Scheduled} phải đủ cho số tác vụ {@code @Scheduled}.
     *
     * <h2>Ca này canh một lỗi đã xảy ra một lần và suýt xảy ra lần thứ hai</h2>
     * {@code spring.task.scheduling.pool.size} mặc định là <b>1</b>. Ở M4, {@code JobRunner}
     * chạy job đồng bộ trên đúng luồng đó, nên một lần nạp testdata 200MB là {@code
     * StaleJobReaper} không chạy suốt thời gian ấy — và bài kẹt {@code JUDGING} quá lease 120s
     * là R1 ("không mất bài nộp") bị phá. M6 sửa bằng cách cho {@code JobRunner} một executor
     * riêng, nhưng bốn tác vụ còn lại vẫn dùng chung một luồng.
     *
     * <p>Cái nguy hiểm nhất trong bốn cái ấy là {@code AuditPartitionScheduler}: nó chạy
     * {@code CREATE TABLE ... PARTITION OF}, tức là lấy {@code ACCESS EXCLUSIVE} trên
     * {@code audit_log} — bảng đang được ghi liên tục. Kẹt lock ở đó là kẹt luôn reaper, vào
     * 03:15 sáng.
     *
     * <p>Đếm bằng phản chiếu chứ không viết cứng một con số: thêm tác vụ {@code @Scheduled}
     * thứ năm mà quên tăng luồng thì ca này đỏ, chứ không phải một buổi sáng đi tìm lý do
     * reaper không chạy.
     */
    @Test
    @DisplayName("★ đủ luồng cho mọi tác vụ @Scheduled — reaper không xếp hàng sau ai")
    void du_luong_cho_moi_tac_vu_dinh_ky() throws IOException {
        var lop = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .withImportOption(com.tngtech.archunit.core.importer.ImportOption
                        .Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.oj");

        java.util.List<String> tacVu = new java.util.ArrayList<>();
        for (var type : lop) {
            for (var m : type.getMethods()) {
                if (m.isAnnotatedWith(org.springframework.scheduling.annotation.Scheduled.class)) {
                    tacVu.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(tacVu).as("không thấy tác vụ @Scheduled nào — bộ nạp class hỏng?").isNotEmpty();

        // JobRunner tự có executor riêng từ M6, nên nhịp @Scheduled của nó chỉ gửi việc rồi
        // trả về ngay. Nó không cần một luồng để dành.
        long canLuong = tacVu.stream().filter(t -> !t.startsWith("JobRunner.")).count();
        int coLuong = so(Files.readString(YML_API), "size");

        assertThat(coLuong)
                .as("%d tác vụ @Scheduled %s cần chỗ, spring.task.scheduling.pool.size chỉ có %d. "
                        + "Mặc định của Spring là 1, và AuditPartitionScheduler giữ ACCESS "
                        + "EXCLUSIVE trên audit_log — kẹt nó là kẹt StaleJobReaper (R1)",
                        canLuong, tacVu, coLuong)
                .isGreaterThanOrEqualTo((int) canLuong);
    }

    /**
     * ★ Ngôn ngữ đang bật trong seed phải chạy được với {@code run.processes} của worker.
     *
     * <h2>Đo, không đoán</h2>
     * Chạy thẳng {@code isolate} với đúng cấu hình worker, cùng một chương trình Java:
     * <pre>
     *   --processes=1    pthread_create failed (EAGAIN) -> VM không khởi động
     *   --processes=8    OutOfMemoryError: unable to create native thread
     *   --processes=32   chạy được
     * </pre>
     * C++ và Python chạy đúng ở {@code processes=1}, nên chỉ Java bị chặn.
     *
     * <h2>Vì sao lỗi này sống được lâu đến thế</h2>
     * {@code CLAUDE.md} mục 6 đòi "smoke test cả 3 ngôn ngữ" khi đụng bảng {@code languages}.
     * Không có test nào chạy Java hay Python qua sandbox — {@code java21} chỉ tồn tại như một
     * chuỗi trong một unit test. Ca này không thay được smoke test thật, nhưng nó chặn đúng
     * cái cửa mà lỗi đã đi qua: <b>bật một ngôn ngữ mà cấu hình sandbox không chạy nổi</b>.
     *
     * <p>Nó tự hết hiệu lực: nâng {@code run.processes} lên ≥ 32 thì ràng buộc biến mất, vì
     * lúc đó Java chạy được thật.
     */
    @Test
    @DisplayName("★ ngôn ngữ đang bật phải chạy được với run.processes hiện tại")
    void ngon_ngu_dang_bat_phai_chay_duoc() throws IOException {
        int javaCan = 32;
        int coProcesses = processesCuaBuocChay(Files.readString(YML_WORKER));
        boolean javaDangBat = ngonNguDangBat(Files.readString(SEED), "java21");

        if (coProcesses < javaCan) {
            assertThat(javaDangBat)
                    .as("oj.worker.sandbox.run.processes = %d, nhưng JVM cần ≥ %d mới khởi động "
                            + "(đo bằng isolate: 1 và 8 đều EAGAIN, 32 chạy). Bật java21 lúc này "
                            + "là mọi bài Java trả RE. Hoặc tắt java21 trong R__seed, hoặc mang "
                            + "`processes` vào bảng languages — xem chú thích ở dòng java21",
                            coProcesses, javaCan)
                    .isFalse();
        }
    }

    /** {@code processes} của khối {@code run:}, không phải của {@code compile:} (cùng tên khoá). */
    private static int processesCuaBuocChay(String yml) {
        String[] dong = yml.split("\n");
        for (int i = 0; i < dong.length; i++) {
            if (!dong[i].trim().equals("run:")) {
                continue;
            }
            for (int j = i + 1; j < dong.length; j++) {
                Matcher m = Pattern.compile("^\\s*processes:\\s*(\\d+)").matcher(dong[j]);
                if (m.find()) {
                    return Integer.parseInt(m.group(1));
                }
            }
        }
        throw new AssertionError("không thấy run.processes trong " + YML_WORKER);
    }

    /** Cờ {@code enabled} của một dòng ngôn ngữ trong {@code R__seed_du_lieu_tham_chieu.sql}. */
    private static boolean ngonNguDangBat(String seed, String code) {
        int batDau = seed.indexOf("('" + code + "'");
        assertThat(batDau).as("không thấy ngôn ngữ '%s' trong seed", code).isNotNegative();
        String dong = seed.substring(batDau, seed.indexOf(")", batDau));
        assertThat(dong).as("dòng '%s' phải khai rõ TRUE hoặc FALSE cho cột enabled", code)
                .containsAnyOf("TRUE", "FALSE");
        return dong.contains("TRUE");
    }

    // -------------------------------------------------------------------------
    // Ba hàm đọc YAML thô. KHÔNG dùng SnakeYAML: nó sẽ đọc được cả những khoá trùng tên ở
    // nhánh khác và trả về nhánh cuối cùng, im lặng. Ở đây mỗi khoá cần đọc là duy nhất
    // trong file của nó, nên một biểu thức chính quy neo vào tên khoá là ít bất ngờ hơn.
    // -------------------------------------------------------------------------

    private static String chuoi(String yml, String khoa) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(khoa) + ":\\s*(\\S+)").matcher(yml);
        assertThat(m.find()).as("không thấy khoá '%s'", khoa).isTrue();
        return m.group(1);
    }

    private static int so(String yml, String khoa) {
        String v = chuoi(yml, khoa);
        Matcher m = Pattern.compile("(\\d+)").matcher(v);   // bóc ${ENV:6} nếu có
        assertThat(m.find()).as("khoá '%s' không chứa số: %s", khoa, v).isTrue();
        return Integer.parseInt(m.group(1));
    }

    private static Duration thoiGian(String yml, String khoa) {
        String v = chuoi(yml, khoa);
        Matcher m = Pattern.compile("(\\d+)(ms|s|m|h)").matcher(v);
        assertThat(m.find()).as("khoá '%s' không phải một khoảng thời gian: %s", khoa, v).isTrue();
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "ms" -> Duration.ofMillis(n);
            case "s" -> Duration.ofSeconds(n);
            case "m" -> Duration.ofMinutes(n);
            default -> Duration.ofHours(n);
        };
    }
}
