package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.application.published.TestdataBytes;
import dev.oj.problems.domain.TestdataKeys;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Optional;

/**
 * Hiện thực {@link TestdataBytes} bằng {@link TestdataStore} — Bước nối worker với kho.
 *
 * <p>Mỏng một cách cố ý: nó không thêm hành vi nào, nó <b>bớt</b> hành vi. Xem javadoc của
 * {@link TestdataBytes} về việc vì sao {@code judging} không được cầm {@code TestdataStore}.
 *
 * <h2>Kiểm định dạng hash TRƯỚC khi hỏi kho</h2>
 * Không phải để tối ưu. {@code sha256} đến từ một path variable, tức là từ mạng — và nó được
 * ghép vào khoá đối tượng của kho. Một chuỗi như {@code ../../config} mà đi tới đó là một lỗ
 * đi ngang thư mục. {@link TestdataKeys} nhận đúng 64 ký tự hex thường và không gì khác, nên
 * chốt này biến cả một lớp lỗi thành một câu {@code if}.
 */
@Component
public class StoreTestdataBytes implements TestdataBytes {

    private final TestdataStore store;

    public StoreTestdataBytes(TestdataStore store) {
        this.store = store;
    }

    @Override
    public Optional<InputStream> doc(String sha256) {
        if (!TestdataKeys.hopLe(sha256)) {
            return Optional.empty();
        }
        if (!store.daCo(sha256)) {
            return Optional.empty();
        }
        return Optional.of(store.doc(sha256));
    }
}
