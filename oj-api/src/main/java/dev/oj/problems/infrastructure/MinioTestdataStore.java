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

@Component
public class MinioTestdataStore implements TestdataStore {

    private static final Logger log = LoggerFactory.getLogger(MinioTestdataStore.class);

    private static final String BUCKET = "oj-testdata";

    private static final String BUCKET_HONG =
            "Không chuẩn bị được bucket {}: {}. API vẫn khởi động — nộp bài và xem đề không phụ thuộc kho này; chỉ nạp testdata sẽ hỏng.";

    private static final long PHAN_TAI = 10L * 1024 * 1024;

    private final MinioClient client;

    private volatile boolean bucketSan;

    public MinioTestdataStore(@Value("${OJ_MINIO_ENDPOINT:http://localhost:9000}") String endpoint,
                              @Value("${OJ_MINIO_ACCESS_KEY:ojminio}") String accessKey,
                              @Value("${OJ_MINIO_SECRET_KEY:ojminio123}") String secretKey) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

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

    private static String khoa(String sha256) {
        return TestdataKeys.khoa(sha256);
    }

    private static ProblemsException khoHong(Exception e) {
        log.error("Kho testdata không dùng được", e);
        return ProblemsException.khoTestdataHong();
    }
}
