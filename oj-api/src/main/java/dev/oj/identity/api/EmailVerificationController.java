package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.application.usecase.ConfirmEmailUseCase;
import dev.oj.identity.application.usecase.SendVerificationEmailUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/xac-minh-email")
public class EmailVerificationController {

    private final SendVerificationEmailUseCase gui;
    private final ConfirmEmailUseCase xacNhan;

    public EmailVerificationController(SendVerificationEmailUseCase gui,
                                       ConfirmEmailUseCase xacNhan) {
        this.gui = gui;
        this.xacNhan = xacNhan;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void guiMa() {
        gui.thucHien();
    }

    @PostMapping("/xac-nhan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xacNhanMa(@RequestBody AuthRequests.XacMinhEmail body) {
        xacNhan.thucHien(body.ma());
    }
}
