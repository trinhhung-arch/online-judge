package dev.oj.identity.api;

import dev.oj.identity.application.usecase.AnonymizeAccountUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * FR-AUTH-07 — ADMIN ẩn danh hoá một tài khoản.
 *
 * <h2>{@code POST .../anonymize}, không phải {@code DELETE /users/{id}}</h2>
 * {@code DELETE} nói rằng bản ghi biến mất, và ở đây nó không biến mất — bài nộp, điểm số và
 * thứ hạng của người đó giữ nguyên trong mọi kỳ thi đã diễn ra. Đặt tên động từ đúng với việc
 * thật sự xảy ra là cách rẻ nhất để người dùng API sau này không hiểu nhầm về mức độ không
 * thể hoàn tác của thao tác.
 *
 * <p>Không có endpoint xoá cứng nào cả, ở bất cứ đâu trong hệ thống. Đó là FR-AUTH-07.
 *
 * <p>Không có {@code @RequiresRole} trên file này: nó nằm trên {@link AnonymizeAccountUseCase},
 * và một bản sao ở đây chỉ tạo ra hai chỗ có thể lệch nhau (bất biến #11).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AccountAdminController {

    private final AnonymizeAccountUseCase anonymize;

    public AccountAdminController(AnonymizeAccountUseCase anonymize) {
        this.anonymize = anonymize;
    }

    @PostMapping("/{userId}/anonymize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anDanhHoa(@PathVariable long userId) {
        anonymize.thucHien(userId);
    }
}
