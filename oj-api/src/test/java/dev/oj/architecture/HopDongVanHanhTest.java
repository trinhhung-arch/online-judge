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

    @Test
    @DisplayName("shutdown-grace nằm giữa thời gian chấm một bài và lease 120s")
    void an_han_tat_may_hop_ly() throws IOException {
        String worker = Files.readString(YML_WORKER);
        Duration anHan = thoiGian(worker, "shutdown-grace");
        Duration lease = thoiGian(worker, "lease");

        assertThat(anHan).isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(anHan).isLessThan(lease);
    }

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

        long canLuong = tacVu.stream().filter(t -> !t.startsWith("JobRunner.")).count();
        int coLuong = so(Files.readString(YML_API), "size");

        assertThat(coLuong)
                .as("%d tác vụ @Scheduled %s cần chỗ, spring.task.scheduling.pool.size chỉ có %d. "
                        + "Mặc định của Spring là 1, và AuditPartitionScheduler giữ ACCESS "
                        + "EXCLUSIVE trên audit_log — kẹt nó là kẹt StaleJobReaper (R1)",
                        canLuong, tacVu, coLuong)
                .isGreaterThanOrEqualTo((int) canLuong);
    }

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

    private static boolean ngonNguDangBat(String seed, String code) {
        int batDau = seed.indexOf("('" + code + "'");
        assertThat(batDau).as("không thấy ngôn ngữ '%s' trong seed", code).isNotNegative();
        String dong = seed.substring(batDau, seed.indexOf(")", batDau));
        assertThat(dong).as("dòng '%s' phải khai rõ TRUE hoặc FALSE cho cột enabled", code)
                .containsAnyOf("TRUE", "FALSE");
        return dong.contains("TRUE");
    }

    private static String chuoi(String yml, String khoa) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(khoa) + ":\\s*(\\S+)").matcher(yml);
        assertThat(m.find()).as("không thấy khoá '%s'", khoa).isTrue();
        return m.group(1);
    }

    private static int so(String yml, String khoa) {
        String v = chuoi(yml, khoa);
        Matcher m = Pattern.compile("(\\d+)").matcher(v);
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
