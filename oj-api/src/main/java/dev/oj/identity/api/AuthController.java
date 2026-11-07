package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.api.dto.SessionResponse;
import dev.oj.identity.application.usecase.LoginUseCase;
import dev.oj.identity.application.usecase.LogoutUseCase;
import dev.oj.identity.application.usecase.RefreshSessionUseCase;
import dev.oj.identity.application.usecase.RegisterUserUseCase;
import dev.oj.platform.security.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final RegisterUserUseCase register;
    private final LoginUseCase login;
    private final RefreshSessionUseCase refresh;
    private final LogoutUseCase logout;

    private final dev.oj.platform.config.AppProperties properties;

    public AuthController(RegisterUserUseCase register, LoginUseCase login,
                          RefreshSessionUseCase refresh, LogoutUseCase logout,
                          dev.oj.platform.config.AppProperties properties) {
        this.register = register;
        this.login = login;
        this.refresh = refresh;
        this.logout = logout;
        this.properties = properties;
    }

    @GetMapping("/captcha")
    public Map<String, Object> captcha() {
        var t = properties.auth().turnstile();
        return Map.of("enabled", t.enabled(), "siteKey", t.siteKey() == null ? "" : t.siteKey());
    }

    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> dangKy(@RequestBody AuthRequests.Register body,
                                                     HttpServletRequest request) {
        long id = register.thucHien(body.handle(), body.email(),
                body.displayName(), body.password(), ClientIp.cua(request),
                body.captchaToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("userId", id));
    }

    @PostMapping("/login")
    public SessionResponse dangNhap(@RequestBody AuthRequests.Login body,
                                    HttpServletRequest request) {
        return SessionResponse.tu(login.thucHien(body.dinhDanh(), body.password(),
                request.getHeader("User-Agent"), ClientIp.cua(request),
                body.maHaiLop()));
    }

    @PostMapping("/refresh")
    public SessionResponse lamMoi(@RequestBody AuthRequests.Refresh body,
                                  HttpServletRequest request) {
        return SessionResponse.tu(refresh.thucHien(body.refreshToken(),
                request.getHeader("User-Agent"), ClientIp.cua(request)));
    }

    @PostMapping("/logout")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void dangXuat(@RequestBody AuthRequests.Refresh body) {
        logout.thucHien(body.refreshToken());
    }
}
