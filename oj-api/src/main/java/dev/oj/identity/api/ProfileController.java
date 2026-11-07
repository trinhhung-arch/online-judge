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

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void doiMatKhau(@RequestBody AuthRequests.ChangePassword body) {
        changePassword.thucHien(body.matKhauCu(), body.matKhauMoi());
    }
}
