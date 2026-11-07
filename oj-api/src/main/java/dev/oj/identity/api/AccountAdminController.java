package dev.oj.identity.api;

import dev.oj.identity.api.dto.AdminUserRequests;
import dev.oj.identity.application.port.UserRepository;
import dev.oj.identity.application.usecase.AnonymizeAccountUseCase;
import dev.oj.identity.application.usecase.ListUsersUseCase;
import dev.oj.identity.application.usecase.ManageUserUseCase;
import dev.oj.platform.web.CursorPage;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AccountAdminController {

    private final AnonymizeAccountUseCase anonymize;
    private final ManageUserUseCase quanLy;
    private final ListUsersUseCase danhSach;

    public AccountAdminController(AnonymizeAccountUseCase anonymize, ManageUserUseCase quanLy,
                                  ListUsersUseCase danhSach) {
        this.danhSach = danhSach;
        this.anonymize = anonymize;
        this.quanLy = quanLy;
    }

    @GetMapping
    public CursorPage<UserRepository.TomTatNguoiDung> danhSach(
            @RequestParam(required = false) String tim,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return danhSach.thucHien(tim, cursor, size);
    }

    @PostMapping("/{userId}/anonymize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anDanhHoa(@PathVariable long userId) {
        anonymize.thucHien(userId);
    }

    @PostMapping("/{userId}/role")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void doiVaiTro(@PathVariable long userId,
                          @Valid @RequestBody AdminUserRequests.DoiVaiTro than) {
        quanLy.doiVaiTro(userId, than.vaiTro());
    }

    @PostMapping("/{userId}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void datHoatDong(@PathVariable long userId,
                            @Valid @RequestBody AdminUserRequests.DatHoatDong than) {
        quanLy.datHoatDong(userId, than.hoatDong());
    }
}
