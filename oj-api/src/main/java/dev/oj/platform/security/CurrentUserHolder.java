package dev.oj.platform.security;

import dev.oj.platform.security.CurrentUserProvider.CurrentUser;

final class CurrentUserHolder {

    private record KetQua(CurrentUser nguoiDung, AuthorizationException loi,
                          boolean daQuaCongHaiLop) {
    }

    private static final ThreadLocal<KetQua> HIEN_TAI = new ThreadLocal<>();

    private CurrentUserHolder() {
    }

    static void dat(CurrentUser nguoiDung) {
        HIEN_TAI.set(new KetQua(nguoiDung, null, false));
    }

    static void datSauCongHaiLop(CurrentUser hieuLuc) {
        HIEN_TAI.set(new KetQua(hieuLuc, null, true));
    }

    static boolean daQuaCongHaiLop() {
        KetQua ketQua = HIEN_TAI.get();
        return ketQua != null && ketQua.daQuaCongHaiLop();
    }

    static void datLoi(AuthorizationException loi) {
        HIEN_TAI.set(new KetQua(null, loi, false));
    }

    static void xoa() {
        HIEN_TAI.remove();
    }

    static CurrentUser batBuoc() {
        KetQua ketQua = HIEN_TAI.get();
        if (ketQua == null) {
            throw AuthorizationException.chuaDangNhap();
        }
        if (ketQua.loi() != null) {
            throw ketQua.loi();
        }
        return ketQua.nguoiDung();
    }

    static Long idNeuCo() {
        KetQua ketQua = HIEN_TAI.get();
        return ketQua == null || ketQua.loi() != null || ketQua.nguoiDung() == null
                ? null : ketQua.nguoiDung().id();
    }
}
