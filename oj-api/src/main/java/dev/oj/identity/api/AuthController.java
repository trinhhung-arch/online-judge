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

/**
 * Bốn cửa của vòng đời phiên — FR-AUTH-01, 02, 03.
 *
 * <h2>Controller ở đây KHÔNG kiểm quyền, và đó là bất biến #11</h2>
 * Không một câu {@code if} nào về vai trò trong file này. Bốn endpoint dưới đây gọi bốn
 * use-case, và cả bốn use-case đó đều mang {@code @PublicAccess} với lý do viết ra thành chữ.
 * Quyết định "ai được gọi" nằm ở đó, nơi mà consumer, job nền và test đều phải đi qua.
 *
 * <p>Việc duy nhất của controller này ngoài chuyển tiếp: lấy {@code User-Agent} và
 * {@link ClientIp} ra khỏi {@code HttpServletRequest} — hai thứ chỉ tồn tại ở tầng HTTP, và
 * use-case không được biết {@code HttpServletRequest} là gì (luật ArchUnit 2c).
 */
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

    /**
     * FR-AUTH-01. Trả {@code 201} kèm {@code userId} — <b>không</b> tự đăng nhập luôn.
     *
     * <p>Đăng ký xong đăng nhập ngay là tiện, nhưng nó gộp hai hành động mà một ngày nào đó
     * sẽ tách ra (xác minh email ở v1.1, FR-AUTH-09). Tách sẵn từ bây giờ thì client không
     * phải sửa gì vào ngày đó.
     */
    /**
     * Cấu hình captcha cho giao diện — công khai, không cần đăng nhập.
     *
     * <p>Chỉ trả {@code siteKey} (khoá công khai) và cờ bật/tắt. Giao diện dùng nó để quyết
     * định có dựng widget hay không: máy dev tắt captcha thì trang đăng ký không hiện ô nào,
     * và không phải sửa file tĩnh nào cả.
     */
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

    /** FR-AUTH-02. */
    @PostMapping("/login")
    public SessionResponse dangNhap(@RequestBody AuthRequests.Login body,
                                    HttpServletRequest request) {
        return SessionResponse.tu(login.thucHien(body.dinhDanh(), body.password(),
                request.getHeader("User-Agent"), ClientIp.cua(request),
                body.maHaiLop()));
    }

    /** FR-AUTH-02 — xoay vòng refresh token, xem {@link RefreshSessionUseCase}. */
    @PostMapping("/refresh")
    public SessionResponse lamMoi(@RequestBody AuthRequests.Refresh body,
                                  HttpServletRequest request) {
        return SessionResponse.tu(refresh.thucHien(body.refreshToken(),
                request.getHeader("User-Agent"), ClientIp.cua(request)));
    }

    /** FR-AUTH-03. Luôn {@code 204}, kể cả khi token không tồn tại — xem {@link LogoutUseCase}. */
    @PostMapping("/logout")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void dangXuat(@RequestBody AuthRequests.Refresh body) {
        logout.thucHien(body.refreshToken());
    }
}
