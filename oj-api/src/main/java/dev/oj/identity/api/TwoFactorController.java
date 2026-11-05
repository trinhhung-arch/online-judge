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

@RestController
@RequestMapping("/api/v1/me/2fa")
public class TwoFactorController {

    private final TwoFactorUseCase useCase;

    public TwoFactorController(TwoFactorUseCase useCase) {
        this.useCase = useCase;
    }

    @GetMapping
    public Map<String, Boolean> trangThai() {
        return Map.of("daBat", useCase.daBat());
    }

    @PostMapping("/bat-dau")
    public Map<String, String> batDau() {
        var banNhap = useCase.batDau();
        return Map.of("secretBase32", banNhap.secretBase32(),
                "otpauthUri", banNhap.otpauthUri());
    }

    @PostMapping("/xac-nhan")
    public Map<String, List<String>> xacNhan(@RequestBody AuthRequests.XacNhanHaiLop body) {
        return Map.of("maDuPhong", useCase.xacNhan(body.ma()));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void tat(@RequestBody AuthRequests.TatHaiLop body) {
        useCase.tat(body.password(), body.ma());
    }
}
