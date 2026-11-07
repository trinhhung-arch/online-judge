package dev.oj.identity.application.usecase;

import dev.oj.identity.application.port.UserRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.platform.web.CursorPage;
import org.springframework.stereotype.Service;

import java.util.List;

@RequiresRole(Role.ADMIN)
@Service
public class ListUsersUseCase {

    private final UserRepository users;
    private final AppProperties properties;

    public ListUsersUseCase(UserRepository users, AppProperties properties) {
        this.users = users;
        this.properties = properties;
    }

    public CursorPage<UserRepository.TomTatNguoiDung> thucHien(String tim, String cursor,
                                                               Integer size) {
        int gioiHan = CursorPage.clampSize(size,
                properties.page().defaultSize(), properties.page().maxSize());

        List<UserRepository.TomTatNguoiDung> dong =
                users.danhSach(tim, docCursor(cursor), gioiHan + 1);

        return CursorPage.of(dong, gioiHan, t -> String.valueOf(t.id()));
    }

    private static Long docCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(cursor.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
