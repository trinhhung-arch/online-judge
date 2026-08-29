package dev.oj.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ★ Vai trò tối thiểu để gọi use-case này — Bước 4.6, FR-AUTH-06, bất biến #11.
 *
 * <h2>Vì sao gắn lên use-case chứ không lên controller</h2>
 * Vì controller không phải lối vào duy nhất. Cùng một use-case còn được gọi từ consumer
 * RabbitMQ, từ job nền, từ một controller thứ hai viết sau, từ một integration test. Đặt
 * chốt kiểm ở controller là đặt nó ở <b>một</b> trong những lối vào — <i>"một request API
 * trực tiếp bỏ qua UI là chuyện 5 phút"</i> (CLAUDE.md bất biến #11).
 *
 * <p>Đặt ở đây thì không có lối vào nào đi vòng được, vì mọi lối vào đều phải đi qua chính
 * đối tượng mang annotation này.
 *
 * <h2>★ Đây là SÀN, không phải toàn bộ phép kiểm</h2>
 * Annotation này chỉ trả lời <i>"vai trò có đủ cao không"</i>. Nó <b>không</b> trả lời
 * <i>"dữ liệu này có phải của người đó không"</i>, và đừng bao giờ dùng nó cho câu hỏi thứ hai.
 *
 * <p>Quyền theo sở hữu — SETTER chỉ sửa đề của mình, người dùng chỉ xem bài nộp của mình —
 * phải nằm trong <b>điều kiện WHERE của câu query</b>, không phải ở đây và cũng không phải
 * trong một câu {@code if} sau khi đã load ({@code oj-api/CLAUDE.md} mục 2, Bước 4.8). Một
 * use-case {@code @RequiresRole(USER)} vẫn có thể là một lỗ hổng IDOR toàn tập nếu câu query
 * của nó quên mệnh đề chủ sở hữu.
 *
 * <h2>Ba khả năng, và use-case nào cũng phải chọn một</h2>
 * {@code RequiresRole} · {@link PublicAccess} · {@link InternalAccess}. LUẬT 8 của
 * {@code ArchitectureTest} fail CI nếu một class {@code *UseCase} không mang cái nào — nên
 * "quên nghĩ về phân quyền" không còn là một trạng thái tồn tại được.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresRole {

    /**
     * Vai trò tối thiểu, so bằng {@link Role#atLeast(Role)}.
     *
     * <p>{@link Role#USER} nghĩa là <i>"cần đăng nhập"</i> — mọi vai trò đều thoả, nhưng khách
     * chưa đăng nhập thì không.
     */
    Role value() default Role.USER;
}
