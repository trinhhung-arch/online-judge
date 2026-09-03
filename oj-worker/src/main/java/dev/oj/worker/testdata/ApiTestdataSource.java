package dev.oj.worker.testdata;

import dev.oj.worker.client.JudgeApiClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * ★ Nguồn testdata <b>thật</b>: tải qua API bằng {@code JudgeEndpoints.TESTDATA}.
 *
 * <h2>Nó thay thế cái gì</h2>
 * {@link LocalDirectoryTestdataSource} là bản của M2, đọc một thư mục trên máy — và
 * {@code build-order.md} 2.7 ghi rõ "MinIO tới ở Bước 4.11". Bước 4.11 đã làm, nhưng chỉ nửa
 * phía API ({@code MinioTestdataStore}, dùng để <i>ghi</i> lúc nạp đề). Nửa phía worker không
 * ai viết, nên hiện thực duy nhất còn lại đọc một thư mục mà <b>không gì đổ dữ liệu vào</b>:
 * mọi bài nộp trả {@code IE} ngay khi testdata được nạp qua API.
 *
 * <h2>Vì sao lớp này KHÔNG gọi HTTP mà uỷ quyền cho {@link JudgeApiClient}</h2>
 * Luật ArchUnit 7: chỉ {@code worker.client} được biết địa chỉ của API. Điều luật ấy bảo vệ là
 * <i>bề mặt phụ thuộc của worker phải đọc được bằng một package</i> — một lời gọi HTTP mọc ở
 * {@code worker.testdata} là bước đầu tiên của việc worker tự đi lấy dữ liệu nó "cần", và
 * bước thứ hai luôn là một {@code DataSource}.
 *
 * <h2>Không cache ở đây</h2>
 * {@link TestdataFetcher} đã cache theo hash và <b>băm lại</b> thứ tải về trước khi ghi cache
 * ("nguồn xa không được tin"). Thêm một tầng cache nữa ở đây là hai chỗ phải hết hạn cùng
 * nhau. Lớp này chỉ làm đúng một việc, và đó là lý do nó dài mười dòng.
 */
@ConditionalOnProperty(name = "oj.worker.testdata-source", havingValue = "api",
        matchIfMissing = true)
@Component
public class ApiTestdataSource implements TestdataSource {

    private final JudgeApiClient api;

    public ApiTestdataSource(JudgeApiClient api) {
        this.api = api;
    }

    @Override
    public byte[] fetch(String sha256) {
        try {
            return api.fetchTestdata(sha256);
        } catch (RuntimeException e) {
            // Đổi sang ngoại lệ của tầng testdata: JobExecutor bắt nó và trả IE, thay vì để
            // một JudgeApiException lọt lên vòng lặp claim và bị hiểu là "API đang xuống".
            throw new TestdataUnavailableException(
                    "Không tải được testdata " + sha256.substring(0, 8) + "... từ API: "
                            + e.getMessage());
        }
    }
}
