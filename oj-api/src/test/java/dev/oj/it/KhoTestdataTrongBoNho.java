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
final class KhoTestdataTrongBoNho implements TestdataStore {

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
