package dev.oj.worker.boot;

import dev.oj.worker.OjWorkerApplication;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.client.JudgeDoorbell;
import dev.oj.worker.config.GracefulShutdown;
import dev.oj.worker.pipeline.JudgeLoop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>Tiến trình worker có dựng được không.</b> Không kiểm một hành vi nào — và đó là điểm.
 *
 * <h2>Khoảng trống mà file này lấp</h2>
 * Tới trước M6, {@code oj-worker} có gần một trăm test, và <b>tất cả đều dựng đối tượng bằng
 * {@code new}</b>. Cách ấy đúng cho thứ chúng kiểm — sandbox, checker, tính điểm subtask — và
 * mù hoàn toàn với một câu hỏi khác: <i>Spring có nối được dây không</i>.
 *
 * <p>Hậu quả thật, tìm ra ở Bước 6.8: {@code JudgeApiClient} nhận {@code RestClient.Builder},
 * mà bean đó do module {@code spring-boot-restclient} cung cấp — module đi kèm starter web,
 * thứ worker <b>cố ý không có</b> vì nó không phải máy chủ. Tiến trình worker chưa từng khởi
 * động được kể từ M1, và không một test nào đỏ. Xem {@code JudgeApiClientConfig}.
 *
 * <p>Bài học không phải "viết nhiều test hơn" mà là: <b>một bộ test toàn unit test không trả
 * lời được câu hỏi "tiến trình này có chạy được không"</b> — và với một hệ thống hai tiến
 * trình thì đó là câu hỏi quan trọng thứ hai, ngay sau tính đúng đắn.
 *
 * <h2>{@link SpringApplicationBuilder}, không phải {@code @SpringBootTest}</h2>
 * {@code @SpringBootTest} nằm trong {@code spring-boot-starter-test}, và {@code oj-worker}
 * không có nó — thêm một dependency là việc phải hỏi người ({@code CLAUDE.md} mục 5.2).
 * {@code SpringApplicationBuilder} nằm trong {@code spring-boot} lõi, đã có sẵn.
 *
 * <p>Đổi lại còn được một thứ tốt hơn: nó khởi động <b>chính lớp {@code OjWorkerApplication}</b>
 * theo đúng đường mà {@code java -jar} đi, chứ không phải một context do khung test lắp riêng.
 *
 * <h2>Không cần hạ tầng nào</h2>
 * RabbitMQ và sandbox đều tắt. {@code JudgeLoop} vẫn khởi động và vẫn đi hỏi API — API không
 * có ở đây, và điều đó <i>đúng như thiết kế</i>: worker ghi WARN rồi ngủ, không chết. Việc
 * test này xanh cũng chứng minh luôn điều ấy.
 */
class WorkerContextSmokeTest {

    private static ConfigurableApplicationContext khoiDong() {
        // Truyền qua ARGS, không qua .properties(): `properties()` đổ vào nguồn
        // "defaultProperties" — nguồn có ĐỘ ƯU TIÊN THẤP NHẤT, nên application.yml vẫn thắng
        // và `${OJ_INTERNAL_SHARED_SECRET}` (cố ý không có mặc định) vẫn không giải được.
        // Tham số dòng lệnh đứng gần đầu bảng ưu tiên, đúng như khi chạy `java -jar`.
        return new SpringApplicationBuilder(OjWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--oj.worker.rabbit.enabled=false",
                        "--oj.worker.sandbox.enabled=false",
                        "--oj.worker.slots=1",
                        // Không để slot dội request vào một API không tồn tại trong lúc test chạy.
                        "--oj.worker.idle-poll=30s",
                        // Tắt context là gọi GracefulShutdown; 30 giây mặc định biến test này
                        // thành một test chạy 30 giây.
                        "--oj.worker.shutdown-grace=200ms",
                        "--oj.worker.internal-secret=" + "x".repeat(32));
    }

    @Test
    @DisplayName("★ context dựng được, và bốn bean của đường chấm đều có mặt")
    void context_dung_duoc() {
        try (ConfigurableApplicationContext ctx = khoiDong()) {
            assertThat(ctx.getBean(JudgeApiClient.class)).isNotNull();
            assertThat(ctx.getBean(JudgeLoop.class)).isNotNull();
            assertThat(ctx.getBean(JudgeDoorbell.class)).isNotNull();
            assertThat(ctx.getBean(GracefulShutdown.class)).isNotNull();
        }
    }

    /**
     * Bước 6.8: {@code GracefulShutdown} phải dừng <b>sau</b> {@code JudgeLoop}. Spring dừng
     * {@code SmartLifecycle} theo phase <i>giảm dần</i>, nên phase của nó phải nhỏ hơn.
     *
     * <p>Kiểm bằng một khẳng định thay vì tin vào một comment: đảo hai con số này thì việc gửi
     * nốt kết quả chạy <i>trong lúc</i> slot còn đang chấm, và nó gửi một danh sách chưa đầy
     * đủ — một lỗi không có triệu chứng nào ngoài "thỉnh thoảng deploy xong mất vài verdict".
     */
    @Test
    @DisplayName("★ GracefulShutdown dừng SAU JudgeLoop (phase nhỏ hơn)")
    void thu_tu_tat_may_dung() {
        try (ConfigurableApplicationContext ctx = khoiDong()) {
            GracefulShutdown tatMayEm = ctx.getBean(GracefulShutdown.class);
            JudgeLoop loop = ctx.getBean(JudgeLoop.class);

            assertThat(tatMayEm.getPhase())
                    .as("phase nhỏ hơn = dừng sau, vì Spring dừng theo phase giảm dần")
                    .isLessThan(loop.getPhase());
        }
    }
}
