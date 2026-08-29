package dev.oj.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Tiến trình chấm bài — <b>vùng duy nhất trong dự án chạy mã của người lạ</b>.
 *
 * <p>Không phải máy chủ: nó không mở cổng nào, không nhận request nào. Nó PULL việc từ
 * {@code POST /internal/judge/claim} theo nhịp của chính nó. Server không giữ danh sách
 * worker, không heartbeat, không service discovery — bật thêm một worker là nó tự vào việc,
 * <b>không sửa một dòng config nào phía API</b> (S2).
 *
 * <p><b>Không có {@code DataSource} ở đây và sẽ không bao giờ có</b> (bất biến #3).
 * Worker biết đúng hai endpoint HTTP. Nếu một nhiệm vụ có vẻ cần worker đọc DB, dừng lại và
 * hỏi — hầu như luôn có nghĩa là dữ liệu đó phải nằm trong {@code oj-contract}.
 *
 * <p>Bản M1 chỉ dựng khung tiến trình. {@code JudgeLoop}, {@code JudgeApiClient} và
 * {@code ScriptedJudgeRunner} là Bước M1-9; {@code isolate} là M2, và chỉ được cắm vào
 * đúng ngày 14/14 test tấn công xanh trong CI, không sớm hơn một giờ.
 */
@ConfigurationPropertiesScan
@EnableScheduling
@SpringBootApplication
public class OjWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OjWorkerApplication.class, args);
    }
}
