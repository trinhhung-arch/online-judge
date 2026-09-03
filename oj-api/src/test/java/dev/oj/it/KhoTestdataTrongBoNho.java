package dev.oj.it;

import dev.oj.problems.application.port.TestdataStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Kho content-addressed trong bộ nhớ, thay cho MinIO trong integration test.
 *
 * <h2>Vì sao không dựng một container MinIO</h2>
 * Thứ đáng kiểm ở Bước 4.10 là <i>bộ kiểm ZIP + job + ba bảng của V2</i>. MinIO chỉ là một chỗ
 * để byte nằm; thêm một container cho nó làm chậm cả bộ IT mà không chứng minh thêm được bất
 * biến nào. {@code JdbcTestdataRepository} thì <b>vẫn</b> chạy trên Postgres thật, đúng như
 * {@code CLAUDE.md} mục 6 đòi.
 *
 * <p>{@code MinioTestdataStore} được kiểm bằng tay khi chạy thật — và phần khó của nó không
 * phải logic mà là cấu hình endpoint, thứ mà một container giả cũng không kiểm được.
 */
class KhoTestdataTrongBoNho implements TestdataStore {

    /**
     * ★ Cắm bản này làm {@code @Primary} cho <b>cả bộ IT</b>.
     *
     * <h2>Nó gỡ một phụ thuộc mà không ai khai báo</h2>
     * Trước đó, mọi IT chạy với {@code MinioTestdataStore} thật — nghĩa là cả bộ ngầm đòi một
     * MinIO đang chạy. Trên máy dev thì có (docker compose), trên runner CI thì không, và
     * không dòng nào trong {@code PostgresIT} nói ra điều đó. Đúng loại phụ thuộc chỉ lộ ra
     * ở lần chạy CI đầu tiên.
     *
     * <p>Postgres và Redis vẫn là container THẬT — chúng có ngữ nghĩa mà bản giả không có
     * ({@code SKIP LOCKED}, pub/sub qua tiến trình khác). MinIO thì chỉ là một chỗ để byte
     * nằm; hình dạng dữ liệu content-addressed giống hệt nhau ở cả hai bản.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class Dang {

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        KhoTestdataTrongBoNho khoTestdataTrongBoNho() {
            return new KhoTestdataTrongBoNho();
        }
    }

    /**
     * Đặt một nội dung vào kho và trả về hash của nó — dùng để seed cho test đọc.
     *
     * <p>Băm bằng đúng thuật toán mà {@code TestdataKeys} đòi, nên hash trả về đi thẳng vào
     * URL của {@code JudgeEndpoints.TESTDATA} được.
     */
    String them(String noiDungVanBan) {
        byte[] b = noiDungVanBan.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha;
        try {
            byte[] bam = java.security.MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder sb = new StringBuilder(64);
            for (byte x : bam) {
                sb.append(Character.forDigit((x >> 4) & 0xF, 16));
                sb.append(Character.forDigit(x & 0xF, 16));
            }
            sha = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK không có SHA-256", e);
        }
        dat(sha, b);
        return sha;
    }

    private final Map<String, byte[]> noiDung = new HashMap<>();

    /** Đặt sẵn một nội dung mà không đi qua {@link #luu} — dùng để nạp gói ZIP đầu vào. */
    void dat(String sha256, byte[] byteData) {
        noiDung.put(sha256, byteData);
    }

    @Override
    public void luu(String sha256, InputStream in, long soByte) {
        try {
            noiDung.put(sha256, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean daCo(String sha256) {
        return noiDung.containsKey(sha256);
    }

    @Override
    public InputStream doc(String sha256) {
        return new ByteArrayInputStream(noiDung.get(sha256));
    }
}
