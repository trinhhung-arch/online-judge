package dev.oj.worker.testdata;

import dev.oj.contract.Sha256;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContentAddressedCacheTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("ghi rồi đọc lại đúng nội dung")
    void ghiRoiDoc() throws Exception {
        ContentAddressedCache cache = new ContentAddressedCache(root);
        byte[] content = "3 4\n".getBytes(StandardCharsets.UTF_8);
        String sha = Sha256.hexOf(content);

        Path stored = cache.store(sha, content);

        assertThat(Files.readAllBytes(stored)).isEqualTo(content);
        assertThat(cache.find(sha)).contains(stored);
    }

    @Test
    @DisplayName("★ nội dung không khớp hash thì TỪ CHỐI, không ghi cache")
    void tuChoiKhiLechHash() {
        ContentAddressedCache cache = new ContentAddressedCache(root);
        String sha = Sha256.hexOf("nội dung đúng");

        assertThatThrownBy(() -> cache.store(sha, "nội dung hỏng".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(TestdataUnavailableException.class)
                .hasMessageContaining("không khớp hash");

        assertThat(cache.find(sha))
                .as("một byte hỏng lọt vào cache làm MỌI bài của đề đó sai mãi mãi, và triệu "
                        + "chứng là 'đề này ai nộp cũng WA' — không ai nghĩ tới cache")
                .isEmpty();
    }

    @Test
    @DisplayName("chưa có thì trả empty, không tự đi tải")
    void chuaCoThiEmpty() {
        assertThat(new ContentAddressedCache(root).find(Sha256.hexOf("chưa từng thấy"))).isEmpty();
    }

    @Test
    @DisplayName("hash đổi thì cache tự miss — không cần cơ chế invalidate nào")
    void doiHashThiMiss() {
        ContentAddressedCache cache = new ContentAddressedCache(root);
        byte[] cu = "1 2".getBytes(StandardCharsets.UTF_8);
        cache.store(Sha256.hexOf(cu), cu);

        byte[] moi = "1 3".getBytes(StandardCharsets.UTF_8);
        assertThat(cache.find(Sha256.hexOf(moi))).isEmpty();
    }
}
