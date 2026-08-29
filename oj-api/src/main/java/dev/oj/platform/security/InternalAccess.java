package dev.oj.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use-case này <b>không có người dùng</b>: nó được gọi bởi worker qua shared secret, bởi bộ
 * lập lịch, hoặc bởi một consumer hàng đợi. Bước 4.6.
 *
 * <h2>Vì sao không dùng {@link RequiresRole} với một vai trò "SYSTEM"</h2>
 * Vì thêm vai trò thứ tư vào {@link Role} là thêm một hàng vào ma trận hiển thị của
 * {@code frplan.md}, và mỗi ô trong hàng đó là một quyết định về rò rỉ dữ liệu — {@code Role}
 * nói thẳng điều này trong javadoc của nó. Tệ hơn: một vai trò SYSTEM tồn tại là một vai trò
 * có thể vô tình được gán cho một người thật.
 *
 * <p>Cách đúng là nói rằng ở đây <i>không có ai cả</i>. Xác thực nằm ở tầng khác —
 * {@link InternalSecretFilter} cho {@code /internal/**}, hoặc không có tầng nào vì lời gọi
 * không đến từ mạng.
 *
 * <p><b>Hệ quả phải nhớ:</b> use-case mang annotation này không được gọi
 * {@link CurrentUserProvider#current()} — sẽ ném {@code auth.chua_dang_nhap}. Nếu nó cần biết
 * bài nộp thuộc về ai thì đọc từ database, đừng hỏi request.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InternalAccess {

    /** Bắt buộc: ai gọi use-case này, và lớp xác thực nào đứng trước nó. */
    String value();
}
