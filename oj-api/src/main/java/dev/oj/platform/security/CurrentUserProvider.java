package dev.oj.platform.security;

public interface CurrentUserProvider {

    CurrentUser current();

    record CurrentUser(long id, String handle, Role role, boolean adminChuaBatHaiLop) {

        public CurrentUser {
            if (id <= 0) {
                throw new IllegalArgumentException("id phải dương");
            }
            if (handle == null || handle.isBlank()) {
                throw new IllegalArgumentException("handle không được rỗng");
            }
            if (role == null) {
                throw new NullPointerException("role");
            }
            if (adminChuaBatHaiLop && role != Role.SETTER) {
                throw new IllegalArgumentException("adminChuaBatHaiLop chỉ đi với vai trò đã hạ");
            }
        }

        public CurrentUser(long id, String handle, Role role) {
            this(id, handle, role, false);
        }

        CurrentUser haVaiTroViChuaBatHaiLop() {
            return new CurrentUser(id, handle, Role.SETTER, true);
        }

        public boolean isAdmin() {
            return role.isAdmin();
        }

        public boolean owns(long ownerId) {
            return this.id == ownerId;
        }
    }
}
