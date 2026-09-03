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
