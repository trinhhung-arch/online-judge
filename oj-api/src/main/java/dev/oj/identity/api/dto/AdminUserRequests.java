package dev.oj.identity.api.dto;

import dev.oj.platform.security.Role;
import jakarta.validation.constraints.NotNull;

public final class AdminUserRequests {

    private AdminUserRequests() {
    }

    public record DoiVaiTro(@NotNull Role vaiTro) {
    }

    public record DatHoatDong(@NotNull Boolean hoatDong) {
    }
}
