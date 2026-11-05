package dev.oj.platform.security;

public final class GiaLapDanhTinh implements AutoCloseable {

    private GiaLapDanhTinh() {
    }

    public static GiaLapDanhTinh dongVai(long id, String handle, Role role) {
        CurrentUserHolder.dat(new CurrentUserProvider.CurrentUser(id, handle, role));
        return new GiaLapDanhTinh();
    }

    public static GiaLapDanhTinh khach() {
        CurrentUserHolder.xoa();
        return new GiaLapDanhTinh();
    }

    @Override
    public void close() {
        CurrentUserHolder.xoa();
    }
}
