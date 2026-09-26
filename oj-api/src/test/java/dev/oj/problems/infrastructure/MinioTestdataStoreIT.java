package dev.oj.problems.infrastructure;

import dev.oj.problems.domain.ProblemsException;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ {@link MinioTestdataStore} trên MinIO THẬT — lớp duy nhất nói chuyện với kho testdata.
 *
 * <h2>Vì sao có lớp này (2026-09-24)</h2>
 * Bộ IT dùng {@code KhoTestdataTrongBoNho}, nên cho tới hôm nay không test nào chạm MinIO client.
 * Nâng client 8.5.17 → 8.6.0 (vá CVE-2025-59952) kéo theo OkHttp 5 và Bouncy Castle 1.85 — ba
 * thư viện mạng/mật mã đổi cùng lúc dưới một lớp mà "được kiểm bằng tay khi chạy thật". Lần
 * nâng ấy hỏng ngay ở biên dịch ({@code okhttp3.HttpUrl}); một lần hỏng lúc CHẠY thì không
 * có gì bắt được trước khi tới máy thật.
 *
 * <p>Cùng ảnh MinIO với {@code docker-compose.yml}: đo đúng thứ sẽ chạy.
 * Container singleton, không {@code stop()} — cùng lý do với {@code PostgresIT}.
 *
 * <h2>⚠️ CI KHÔNG chạy lớp này (từ 2026-09-26) — chỉ {@code ./mvnw verify} trên máy</h2>
 * MinIO đã thôi phát ảnh công khai cho bản cộng đồng: Docker Hub {@code minio/minio} 404 từ
 * 2026-09-24, và {@code quay.io/minio/minio} trả {@code unauthorized} từ 2026-09-26 — kể cả với
 * token ẩn danh, trong khi repo công khai khác trên quay.io vẫn kéo được. Runner CI không có
 * ảnh nên cả bốn ca chết ở khởi tạo lớp; với {@code build} là check bắt buộc của {@code main},
 * thế là MỌI PR bị chặn. Nên {@code ci.yml} loại nhãn {@link #NHAN}. Máy prod còn ảnh trong
 * cache ({@code linux/arm64}, đúng digest ở dưới), nên {@code ./mvnw verify} trên máy ấy vẫn
 * chạy đủ bốn ca; máy KHÔNG có ảnh thì chạy {@code ./mvnw verify -DexcludedGroups=minio-that}.
 *
 * <p>Gỡ việc loại trừ khi có nguồn ảnh mới — tự build MinIO từ mã nguồn lên GHCR, hoặc một
 * S3 server khác cho riêng test (rà soát 2026-09-24, việc còn mở). Đừng để nó thành vĩnh viễn:
 * đây là lưới DUY NHẤT của đường ghi/đọc testcase ẩn.
 */
@Tag(MinioTestdataStoreIT.NHAN)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MinioTestdataStoreIT {

    /** Nhãn mà {@code ci.yml} loại ra — xem javadoc của lớp. */
    static final String NHAN = "minio-that";

    /**
     * Đúng chuỗi của {@code docker-compose.yml} — quay.io + digest, xem lý do ở đó. Bản đầu dùng
     * {@code minio/minio} của Docker Hub: xanh trên máy dev (ảnh có sẵn), đỏ trên CI với
     * "pull access denied … repository does not exist" (2026-09-24).
     */
    private static final String ANH = "quay.io/minio/minio:RELEASE.2024-10-13T13-34-11Z"
            + "@sha256:9535594ad4122b7a78c6632788a989b96d9199b483d3bd71a5ceae73a922cdfa";
    private static final String NGUOI_DUNG = "ojminio";
    private static final String MAT_KHAU = "ojminio123";

    static final GenericContainer<?> MINIO = new GenericContainer<>(ANH);

    static {
        MINIO.withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", NGUOI_DUNG)
                .withEnv("MINIO_ROOT_PASSWORD", MAT_KHAU)
                .withCommand("server", "/data")
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
        MINIO.start();
    }

    private static String diaChi() {
        return "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
    }

    private static MinioTestdataStore kho() {
        return new MinioTestdataStore(diaChi(), NGUOI_DUNG, MAT_KHAU);
    }

    /**
     * ĐẦU TIÊN, trên container còn trống — nên nó kiểm điều kiện ấy trước, không giả định.
     * Javadoc của class gọi {@code luu} là "chỗ thứ hai, và là chỗ cần thiết": API lên trước MinIO
     * thì {@code @PostConstruct} hỏng một lần rồi thôi.
     */
    @Test
    @Order(1)
    @DisplayName("★ luu tự tạo bucket dù bước khởi động chưa chạy — API lên trước MinIO vẫn nạp được testdata")
    void luu_tu_tao_bucket() throws Exception {
        MinioClient tho = MinioClient.builder().endpoint(diaChi()).credentials(NGUOI_DUNG, MAT_KHAU).build();
        assertThat(tho.bucketExists(BucketExistsArgs.builder().bucket("oj-testdata").build()))
                .as("điều kiện của ca này: bucket CHƯA có — không thì ca này không chứng minh gì")
                .isFalse();

        byte[] noiDung = ngauNhien(4096);
        kho().luu(sha256(noiDung), new ByteArrayInputStream(noiDung), noiDung.length);

        assertThat(kho().daCo(sha256(noiDung))).isTrue();
    }

    @Test
    @DisplayName("ghi rồi đọc lại đúng từng byte — biết trước kích thước, và KHÔNG biết (tải từng phần)")
    void ghi_roi_doc_lai() throws Exception {
        var kho = kho();
        kho.chuanBiBucket();
        byte[] biet = ngauNhien(1 << 20);
        byte[] khongBiet = ngauNhien(300_000);

        kho.luu(sha256(biet), new ByteArrayInputStream(biet), biet.length);
        kho.luu(sha256(khongBiet), new ByteArrayInputStream(khongBiet), -1);

        try (InputStream a = kho.doc(sha256(biet)); InputStream b = kho.doc(sha256(khongBiet))) {
            assertThat(a.readAllBytes()).isEqualTo(biet);
            assertThat(b.readAllBytes()).isEqualTo(khongBiet);
        }
    }

    @Test
    @DisplayName("daCo = false cho hash chưa từng ghi — không ném, không nhầm thành 'kho hỏng'")
    void chua_co_thi_false() throws Exception {
        assertThat(kho().daCo(sha256("chưa từng ghi".getBytes()))).isFalse();
    }

    /**
     * Ngoại lệ của MinIO mang endpoint và tên bucket — ra HTTP là lộ hạ tầng. Cổng 1 trên
     * loopback: không ai nghe, nên bị từ chối ngay chứ không chờ hết timeout.
     */
    @Test
    @DisplayName("★ MinIO chết: khởi động không ném; ghi/đọc ném lỗi CỦA MODULE, không lộ endpoint")
    void minio_chet() throws Exception {
        var chet = new MinioTestdataStore("http://127.0.0.1:1", NGUOI_DUNG, MAT_KHAU);
        String hash = sha256(new byte[]{1});

        assertThatCode(chet::chuanBiBucket).doesNotThrowAnyException();
        assertThatThrownBy(() -> chet.luu(hash, new ByteArrayInputStream(new byte[]{1}), 1))
                .isInstanceOf(ProblemsException.class)
                .hasFieldOrPropertyWithValue("code", "problem.kho_testdata_hong")
                .message().doesNotContain("127.0.0.1").doesNotContain("oj-testdata");
        assertThatThrownBy(() -> chet.doc(hash)).isInstanceOf(ProblemsException.class);
    }

    private static byte[] ngauNhien(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
