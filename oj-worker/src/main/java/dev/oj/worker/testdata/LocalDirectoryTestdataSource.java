package dev.oj.worker.testdata;

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
 * <h2>★ {@code @ConditionalOnMissingBean} đã bị GỠ ở M6 — nó là một lỗi, không phải một lựa chọn</h2>
 * Bản M2 mang {@code @ConditionalOnMissingBean(TestdataSource.class)} trên chính class này,
 * với ý định "bean này được phép thua bất kỳ hiện thực nào khác". Ý định đúng; cơ chế thì sai.
 *
 * <p>{@code @ConditionalOnMissingBean} <b>chỉ đáng tin trong auto-configuration</b>, nơi
 * Spring bảo đảm nó chạy sau khi mọi bean của người dùng đã đăng ký. Trên một class được
 * component scan, nó được đánh giá <i>trong lúc</i> quét — thời điểm mà bean factory còn gần
 * như rỗng — và kết quả là bean này <b>không bao giờ được đăng ký</b>. Tiến trình worker chết
 * lúc dựng context với {@code No qualifying bean of type 'TestdataSource'}.
 *
 * <p>Không test nào bắt được suốt từ M2 tới M6, vì mọi test của {@code oj-worker} đều dựng
 * đối tượng bằng {@code new}. Xem {@code WorkerContextSmokeTest}.
 *
 * <p>Cách giữ đúng ý định ban đầu mà không cần annotation: một {@code @Component} trần. Ngày
 * có {@code MinioTestdataSource} thứ hai, Spring ném {@code NoUniqueBeanDefinitionException}
 * — <b>đúng cái "hỏng ồn ào" mà ghi chú cũ nói là muốn</b>, và lúc đó người thêm nó chọn
 * tường minh bằng {@code @Primary} hoặc một thuộc tính cấu hình.
 */
@Component
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
