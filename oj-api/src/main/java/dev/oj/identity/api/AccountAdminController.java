package dev.oj.identity.api;

import dev.oj.identity.api.dto.AdminUserRequests;
import dev.oj.identity.application.usecase.AnonymizeAccountUseCase;
import dev.oj.identity.application.usecase.ManageUserUseCase;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN quản lý tài khoản: FR-AUTH-07 (ẩn danh hoá) và FR-ADM-03 (vai trò, vô hiệu hoá).
 *
 * <h2>{@code POST .../anonymize}, không phải {@code DELETE /users/{id}}</h2>
 * {@code DELETE} nói rằng bản ghi biến mất, và ở đây nó không biến mất — bài nộp, điểm số và
 * thứ hạng của người đó giữ nguyên trong mọi kỳ thi đã diễn ra. Đặt tên động từ đúng với việc
 * thật sự xảy ra là cách rẻ nhất để người dùng API sau này không hiểu nhầm về mức độ không
 * thể hoàn tác của thao tác.
 *
 * <p>Không có endpoint xoá cứng nào cả, ở bất cứ đâu trong hệ thống. Đó là FR-AUTH-07, và
 * FR-ADM-03 nói lại đúng điều đó bằng từ khác: <i>"vô hiệu hoá (không xoá cứng)"</i>.
 *
 * <h2>Ba mức, ba ý nghĩa khác nhau — đừng gộp</h2>
 * <pre>
 *   DISABLED    tạm, đảo ngược được   dữ liệu định danh còn nguyên   FR-ADM-03
 *   ANONYMIZED  vĩnh viễn             email + mật khẩu bị xoá thật   FR-AUTH-07
 *   (xoá cứng)  không tồn tại         —                              không bao giờ
 * </pre>
 *
 * <p>Không có {@code @RequiresRole} trên file này: nó nằm trên {@link AnonymizeAccountUseCase},
 * và một bản sao ở đây chỉ tạo ra hai chỗ có thể lệch nhau (bất biến #11).
 */
@RestController
@RequestMapping("/api/v1/admin/users")
public class AccountAdminController {

    private final AnonymizeAccountUseCase anonymize;
    private final ManageUserUseCase quanLy;

    public AccountAdminController(AnonymizeAccountUseCase anonymize, ManageUserUseCase quanLy) {
        this.anonymize = anonymize;
        this.quanLy = quanLy;
    }

    @PostMapping("/{userId}/anonymize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anDanhHoa(@PathVariable long userId) {
        anonymize.thucHien(userId);
    }

    /** FR-ADM-03. {@code POST} chứ không {@code PATCH}: đây là một thao tác, không phải một
     *  phép sửa từng phần — và nó kéo theo việc thu hồi mọi phiên đăng nhập của người đó. */
    @PostMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void doiVaiTro(@PathVariable long userId,
                          @Valid @RequestBody AdminUserRequests.DoiVaiTro than) {
        quanLy.doiVaiTro(userId, than.vaiTro());
    }

    /** FR-ADM-03 — vô hiệu hoá hoặc mở lại. Một endpoint cho cả hai chiều: hai endpoint
     *  {@code /disable} và {@code /enable} là hai chỗ phải nhớ giữ đối xứng. */
    @PostMapping("/{userId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void datHoatDong(@PathVariable long userId,
                            @Valid @RequestBody AdminUserRequests.DatHoatDong than) {
        quanLy.datHoatDong(userId, than.hoatDong());
    }
}
