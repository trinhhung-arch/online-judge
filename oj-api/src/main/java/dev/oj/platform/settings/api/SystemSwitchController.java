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

@RestController
@RequestMapping("/api/v1/admin/settings")
public class SystemSwitchController {

    private final ToggleSystemSwitchUseCase congTac;

    public SystemSwitchController(ToggleSystemSwitchUseCase congTac) {
        this.congTac = congTac;
    }

    @GetMapping
    public Collection<ToggleSystemSwitchUseCase.CongTac> doc() {
        return congTac.doc().values();
    }

    @PostMapping("/{khoa}")
    @ResponseStatus(HttpStatus.OK)
    public ToggleSystemSwitchUseCase.CongTac dat(@PathVariable String khoa,
                                                 @RequestBody DatCongTac body) {
        return congTac.dat(khoa, Boolean.TRUE.equals(body.bat()));
    }

    public record DatCongTac(Boolean bat) {
    }
}
