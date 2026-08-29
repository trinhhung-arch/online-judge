package dev.oj.worker.testdata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Nguồn testdata đọc từ một thư mục trên máy — hiện thực duy nhất tồn tại ở M2.
 *
 * <p>Kho thật là MinIO và nó tới ở Bước 4.11. Cho tới lúc đó, hai người vẫn cần chạy được
 * đường chấm đầy đủ trên máy mình, và một thư mục đánh địa chỉ bằng hash <b>chính là</b> thứ
 * MinIO trông như thế từ phía worker — nên đây không phải bản giả, nó là cùng một hình dạng
 * dữ liệu với một transport khác.
 *
 * <p>{@code @ConditionalOnMissingBean} nằm trên phương thức {@code @Bean} chứ không trên
 * class là có lý do — nhưng ở đây nó nằm trên class vì bean này <b>được phép</b> thua bất kỳ
 * hiện thực nào khác, và ngày M4 thêm {@code MinioTestdataSource} thì hỏng ồn ào
 * ({@code NoUniqueBeanDefinitionException}) vẫn tốt hơn là chọn nhầm trong im lặng.
 */
@Component
@ConditionalOnMissingBean(TestdataSource.class)
public class LocalDirectoryTestdataSource implements TestdataSource {

    private final Path root;

    public LocalDirectoryTestdataSource(
            @org.springframework.beans.factory.annotation.Value(
                    "${oj.worker.sandbox.cache.dir}/testdata-store") Path root) {
        this.root = root;
    }

    @Override
    public byte[] fetch(String sha256) {
        Path file = root.resolve(sha256.substring(0, 2)).resolve(sha256);
        if (!Files.isRegularFile(file)) {
            throw new TestdataUnavailableException(
                    "Không có testdata " + sha256 + " trong kho cục bộ " + root
                            + ". Kho thật (MinIO) là Bước 4.11 — tới lúc đó, đổ testdata vào "
                            + "thư mục này theo cấu trúc <2 ký tự đầu>/<sha256 đầy đủ>");
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TestdataUnavailableException("Không đọc được testdata " + sha256, e);
        }
    }
}
