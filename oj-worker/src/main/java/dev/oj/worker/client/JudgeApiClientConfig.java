package dev.oj.worker.client;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * ★ Bean {@link RestClient.Builder} cho {@link JudgeApiClient} — <b>sửa một lỗi khiến tiến
 * trình worker chưa từng khởi động được</b>.
 *
 * <h2>Lỗi là gì</h2>
 * {@code JudgeApiClient} nhận {@code RestClient.Builder} qua constructor từ M1. Boot cấu hình
 * sẵn bean ấy trong module {@code spring-boot-restclient}, mà module đó đi kèm
 * {@code spring-boot-starter-web(mvc)} — thứ {@code oj-worker} <b>cố ý không có</b>: worker
 * không phải máy chủ, nó không mở cổng nào ({@code spring.main.web-application-type: none}).
 * Nó chỉ phụ thuộc {@code spring-web} thô, tức là có <i>lớp</i> {@code RestClient} nhưng không
 * có <i>auto-configuration</i> dựng builder.
 *
 * <p>Kết quả: {@code Parameter 1 of constructor in JudgeApiClient required a bean of type
 * 'RestClient.Builder' that could not be found}. Ứng dụng chết ngay lúc dựng context.
 *
 * <h2>Vì sao không test nào bắt được, suốt từ M1 tới M6</h2>
 * Vì <b>không test nào dựng Spring context của worker</b>. {@code ScriptedJudgeRunnerTest},
 * {@code SlotPoolTest}, {@code CheckerTest} là unit test dựng đối tượng bằng {@code new};
 * {@code IsolateJudgeRunnerIT} và {@code HostBenchmarkIT} cũng nối dây bằng tay. Cả hai loại
 * đều đúng cho việc chúng kiểm, và cả hai đều mù với câu hỏi <i>"tiến trình này có chạy được
 * không"</i>.
 *
 * <p>{@code WorkerContextSmokeTest} lấp đúng khoảng trống ấy: nó không kiểm một hành vi nào,
 * nó chỉ dựng context. Một test như thế trông vô nghĩa cho tới ngày nó đỏ.
 *
 * <h2>Vì sao tự khai báo chứ không thêm {@code spring-boot-starter-restclient}</h2>
 * Thêm dependency là việc phải hỏi người ({@code CLAUDE.md} mục 5.2), và ở đây nó không đáng:
 * bean cần dựng là <b>một dòng</b>. Starter kia còn kéo theo auto-configuration của
 * {@code HttpClient}, observability và message converter — bề mặt thêm vào đúng cái máy sẽ
 * chạy mã của người lạ, để đổi lấy một dòng ta viết được.
 *
 * <p>Timeout <b>không</b> đặt ở đây mà ở {@code JudgeApiClient} qua
 * {@code oj.worker.request-timeout} — giữ mọi con số ở một chỗ ({@code CLAUDE.md} mục 7).
 */
@Configuration
public class JudgeApiClientConfig {

    @Bean
    public RestClient.Builder judgeRestClientBuilder() {
        return RestClient.builder();
    }
}
