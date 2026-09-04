package dev.oj.platform.settings.api;

import dev.oj.platform.settings.application.ToggleSystemSwitchUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;

/**
 * Công tắc lúc đang chạy — FR-ADM-06. {@code /api/v1/admin/settings}.
 *
 * <h2>Không có {@code @RequiresRole} ở đây</h2>
 * Nó nằm trên {@link ToggleSystemSwitchUseCase} (bất biến #11). Một request gọi thẳng API,
 * bỏ qua giao diện, là chuyện năm phút — chốt phải ở nơi mọi đường đều đi qua.
 *
 * <h2>Vì sao {@code POST} chứ không phải {@code PUT}</h2>
 * Trùng với mọi hành động quản trị khác của hệ thống này ({@code /publish}, {@code /retire},
 * {@code /reveal}, {@code /rejudge}): đây là một <i>hành động có hậu quả</i> để lại một dòng
 * {@code audit_log}, không phải việc đặt lại một tài nguyên. Giữ một hình dạng cho cả nhóm
 * để người viết client không phải tra từng cái.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class SystemSwitchController {

    private final ToggleSystemSwitchUseCase congTac;

    public SystemSwitchController(ToggleSystemSwitchUseCase congTac) {
        this.congTac = congTac;
    }

    /**
     * Mọi công tắc đổi được, kèm giá trị hiện tại và câu mô tả hậu quả.
     *
     * <p>Trả về một <b>danh sách</b> chứ không phải một object khoá-giá trị: giao diện dựng
     * được ô cho công tắc mới mà không cần sửa gì, và câu mô tả chỉ tồn tại ở một nơi.
     *
     * <p><b>Không phân trang, và đó không phải một ngoại lệ của bất biến #8.</b> Bất biến ấy
     * canh những danh sách <i>lớn theo dữ liệu</i> — {@code submissions} sẽ có hàng triệu
     * dòng. Danh sách này dài đúng bằng số hằng trong danh sách trắng của use-case: hai. Nó
     * chỉ dài ra khi có người sửa mã nguồn, và lúc ấy họ đang nhìn thẳng vào nó.
     */
    @GetMapping
    public Collection<ToggleSystemSwitchUseCase.CongTac> doc() {
        return congTac.doc().values();
    }

    /** @param khoa phải nằm trong danh sách trắng của use-case; khoá lạ trả 400 */
    @PostMapping("/{khoa}")
    @ResponseStatus(HttpStatus.OK)
    public ToggleSystemSwitchUseCase.CongTac dat(@PathVariable String khoa,
                                                 @RequestBody DatCongTac body) {
        return congTac.dat(khoa, Boolean.TRUE.equals(body.bat()));
    }

    /**
     * @param bat cố ý là {@code Boolean} chứ không phải {@code boolean}: thân request thiếu
     *            trường sẽ thành {@code null}, và {@code null} phải đọc là "tắt" một cách rõ
     *            ràng ở chỗ gọi, chứ không phải thành {@code false} lặng lẽ do Java
     */
    public record DatCongTac(Boolean bat) {
    }
}
