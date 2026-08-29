package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.api.dto.ProfileResponse;
import dev.oj.identity.api.dto.UpdateProfileRequest;
import dev.oj.identity.application.usecase.ChangePasswordUseCase;
import dev.oj.identity.application.usecase.GetProfileUseCase;
import dev.oj.identity.application.usecase.UpdateProfileUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hồ sơ của chính mình — FR-AUTH-04, FR-AUTH-05.
 *
 * <h2>Không có {@code /users/{id}} ở đây, và đó là biện pháp chống IDOR rẻ nhất</h2>
 * Cả ba endpoint đều nói về <i>người đang gọi</i>, lấy từ {@code CurrentUserProvider}. Không
 * có tham số id nghĩa là không có gì để sửa trong URL — lỗ hổng IDOR phổ biến nhất
 * ({@code /users/1/password}) không tồn tại được ở đây, không phải vì có ai đó kiểm đúng mà
 * vì không có đường nào để kiểm sai.
 *
 * <p>Đây là hình mẫu cho Bước 4.8: cách chắc chắn nhất để không quên một phép kiểm là thiết
 * kế sao cho không cần phép kiểm đó.
 */
@RestController
@RequestMapping("/api/v1/me")
public class ProfileController {

    private final GetProfileUseCase getProfile;
    private final UpdateProfileUseCase updateProfile;
    private final ChangePasswordUseCase changePassword;

    public ProfileController(GetProfileUseCase getProfile, UpdateProfileUseCase updateProfile,
                             ChangePasswordUseCase changePassword) {
        this.getProfile = getProfile;
        this.updateProfile = updateProfile;
        this.changePassword = changePassword;
    }

    @GetMapping
    public ProfileResponse xem() {
        return ProfileResponse.tu(getProfile.thucHien());
    }

    @PatchMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sua(@RequestBody UpdateProfileRequest body) {
        updateProfile.thucHien(body.displayName(), body.preferredLanguageId());
    }

    /**
     * FR-AUTH-04. Thành công nghĩa là <b>mọi phiên bị thu hồi</b>, kể cả phiên vừa gọi request
     * này — client phải đăng nhập lại. Xem {@link ChangePasswordUseCase}.
     */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void doiMatKhau(@RequestBody AuthRequests.ChangePassword body) {
        changePassword.thucHien(body.matKhauCu(), body.matKhauMoi());
    }
}
