package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.application.usecase.TwoFactorUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Xác thực hai lớp cho chính mình — FR-AUTH-09.
 *
 * <h2>Nằm dưới {@code /api/v1/me}, không phải {@code /api/v1/admin}</h2>
 * Ai cũng bật được 2FA cho tài khoản của mình; ADMIN thì <b>bắt buộc</b>. Đặt nó dưới
 * {@code /admin} sẽ tạo ra một vòng lặp không lối ra: {@code TwoFactorGate} chặn mọi bề mặt
 * ADMIN của một ADMIN chưa bật 2FA, kể cả bề mặt dùng để bật.
 *
 * <p>Phân quyền nằm ở {@link TwoFactorUseCase}, không ở đây — bất biến #11.
 */
@RestController
@RequestMapping("/api/v1/me/2fa")
public class TwoFactorController {

    private final TwoFactorUseCase useCase;

    public TwoFactorController(TwoFactorUseCase useCase) {
        this.useCase = useCase;
    }

    /** Giao diện hỏi trước khi vẽ: đang bật thì hiện khối tắt, chưa bật thì hiện nút bật. */
    @GetMapping
    public Map<String, Boolean> trangThai() {
        return Map.of("daBat", useCase.daBat());
    }

    /**
     * Bước 1 — sinh bí mật. Chưa bật gì cả.
     *
     * <p>{@code secretBase32} trả về để người dùng gõ tay khi máy ảnh không quét được. Mã QR
     * dựng ở phía client từ {@code otpauthUri}: sinh ảnh QR ở server nghĩa là bí mật đi thêm
     * một vòng qua tầng ảnh, log truy cập và có thể cả cache của proxy.
     */
    @PostMapping("/bat-dau")
    public Map<String, String> batDau() {
        var banNhap = useCase.batDau();
        return Map.of("secretBase32", banNhap.secretBase32(),
                "otpauthUri", banNhap.otpauthUri());
    }

    /**
     * Bước 2 — xác nhận và bật.
     *
     * @return mười mã dự phòng, <b>chỉ trả về đúng lần này</b>. Server giữ bản băm nên
     *         không có endpoint nào xem lại được
     */
    @PostMapping("/xac-nhan")
    public Map<String, List<String>> xacNhan(@RequestBody AuthRequests.XacNhanHaiLop body) {
        return Map.of("maDuPhong", useCase.xacNhan(body.ma()));
    }

    /** Bước 3 — tắt. Đòi cả mật khẩu lẫn mã hiện tại. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tat(@RequestBody AuthRequests.TatHaiLop body) {
        useCase.tat(body.password(), body.ma());
    }
}
