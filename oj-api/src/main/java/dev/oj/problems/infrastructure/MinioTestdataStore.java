package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataKeys;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * {@link TestdataStore} trên MinIO — Bước 4.11.
 *
 * <h2>Khoá đối tượng chia hai tầng theo hai ký tự đầu của hash</h2>
 * {@code testdata/ab/ab3f9c...} thay vì {@code testdata/ab3f9c...}. Không phải để đẹp: một
 * thư mục phẳng với hàng trăm nghìn đối tượng làm mọi công cụ liệt kê (kể cả {@code mc ls}
 * lúc đi tìm sự cố) chậm tới mức không dùng được. Đây là khuôn mà git dùng cho {@code objects/}
 * và vì đúng lý do đó.
 *
 * <h2>Vì sao không {@code @ConfigurationProperties}</h2>
 * Ba giá trị, đọc thẳng từ env với {@code @Value}. {@code AppProperties} là chỗ cho <b>ngưỡng
 * và giới hạn</b> — những con số mà một compact constructor có thể đối chiếu và crash lúc
 * boot. Endpoint và khoá truy cập không có gì để đối chiếu; chúng chỉ cần có mặt.
 *
 * <h2>★ Bucket được bảo đảm ở HAI chỗ, và chỗ thứ hai mới là chỗ cần thiết</h2>
 * Lúc khởi động là đường nhanh: nếu MinIO đã sẵn sàng thì mọi thứ xong trước request đầu tiên,
 * và một lỗi cấu hình hiện ra ngay trong log khởi động thay vì hiện ra lúc ai đó nạp testdata.
 *
 * <p>Nhưng khởi động <b>không đủ</b>, và đây là lỗi đã gặp thật khi chạy tay ở Bước 4.11: API
 * lên trước MinIO (thứ tự khởi động của docker-compose không bảo đảm điều ngược lại),
 * {@code @PostConstruct} thất bại một lần rồi thôi, và mọi lần nạp testdata sau đó hỏng
 * <i>vĩnh viễn</i> — cho tới khi ai đó nghĩ ra là phải khởi động lại API. Triệu chứng
 * (<i>"kho dữ liệu test không dùng được"</i>) không hề gợi ra nguyên nhân đó.
 *
 * <p>Nên {@link #luu} tự bảo đảm bucket trước khi ghi. Sau lần thành công đầu tiên thì cờ
 * {@link #bucketSan} tắt hẳn phép kiểm, nên chi phí thường trực bằng 0.
 *
 * <p>MinIO chết vẫn <b>không</b> ngăn API khởi động. Nạp testdata sẽ hỏng với một câu rõ ràng,
 * còn nộp bài và xem đề thì không liên quan gì tới kho này — cùng lập luận với Redis ở
 * {@code RedisSubmissionEventBus}.
 */
@Component
public class MinioTestdataStore implements TestdataStore {

    private static final Logger log = LoggerFactory.getLogger(MinioTestdataStore.class);

    private static final String BUCKET = "oj-testdata";

    private static final String BUCKET_HONG =
            "Không chuẩn bị được bucket {}: {}. API vẫn khởi động — nộp bài và xem đề không phụ thuộc kho này; chỉ nạp testdata sẽ hỏng.";

    /** Phần tải lên mỗi lần khi không biết trước kích thước. 10MB là mức tối thiểu MinIO nhận. */
    private static final long PHAN_TAI = 10L * 1024 * 1024;

    private final MinioClient client;

    /**
     * Đã xác nhận bucket tồn tại. {@code volatile} vì nhiều luồng request cùng đọc; một lần
     * kiểm thừa do đua tranh là vô hại ({@code makeBucket} chỉ được gọi khi chưa có, và lỗi
     * "đã tồn tại" cũng bị nuốt).
     */
    private volatile boolean bucketSan;

    public MinioTestdataStore(@Value("${OJ_MINIO_ENDPOINT:http://localhost:9000}") String endpoint,
                              @Value("${OJ_MINIO_ACCESS_KEY:ojminio}") String accessKey,
                              @Value("${OJ_MINIO_SECRET_KEY:ojminio123}") String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /** Đường nhanh lúc khởi động. Hỏng thì chỉ ghi WARN — {@link #luu} sẽ thử lại. */
    @PostConstruct
    void chuanBiBucket() {
        try {
            baoDamBucket();
        } catch (Exception e) {
            log.warn(BUCKET_HONG, BUCKET, e.toString());
        }
    }

    private void baoDamBucket() throws Exception {
        if (bucketSan) {
            return;
        }
        if (!client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            log.info("Đã tạo bucket {}", BUCKET);
        }
        bucketSan = true;
    }

    @Override
    public void luu(String sha256, InputStream noiDung, long soByte) {
        try {
            // Bảo đảm ở đây, không chỉ lúc khởi động — xem javadoc của class.
            baoDamBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(BUCKET)
                    .object(khoa(sha256))
                    .stream(noiDung, soByte, soByte < 0 ? PHAN_TAI : -1)
                    .build());
        } catch (Exception e) {
            throw khoHong(e);
        }
    }

    @Override
    public boolean daCo(String sha256) {
        try {
            client.statObject(StatObjectArgs.builder().bucket(BUCKET).object(khoa(sha256)).build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            throw khoHong(e);
        }
    }

    @Override
    public InputStream doc(String sha256) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(BUCKET).object(khoa(sha256)).build());
        } catch (Exception e) {
            throw khoHong(e);
        }
    }

    /**
     * Cách bố trí khoá sống ở {@link TestdataKeys} — nó là một quyết định về dữ liệu, không
     * phải về MinIO, và nó phải giữ nguyên nếu ngày nào đó kho đổi nhà.
     */
    private static String khoa(String sha256) {
        return TestdataKeys.khoa(sha256);
    }

    /**
     * Một câu cho người dùng, chi tiết chỉ vào log.
     *
     * <p>Ngoại lệ của MinIO mang theo endpoint, tên bucket, đôi khi cả khoá truy cập trong
     * phần thông điệp. Ném thẳng ra HTTP là để lộ hình dạng hạ tầng nội bộ cho bất kỳ ai nạp
     * một file hỏng ({@code CLAUDE.md} mục 4.2).
     */
    private static ProblemsException khoHong(Exception e) {
        log.error("Kho testdata không dùng được", e);
        return ProblemsException.khoTestdataHong();
    }
}
