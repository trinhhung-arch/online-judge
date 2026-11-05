package dev.oj.platform.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    private final ObjectProvider<TwoFactorGate> congHaiLop;

    public JwtCurrentUserProvider(ObjectProvider<TwoFactorGate> congHaiLop) {
        this.congHaiLop = congHaiLop;
    }

    @Override
    public CurrentUser current() {
        CurrentUser nguoiGoi = CurrentUserHolder.batBuoc();
        if (!nguoiGoi.isAdmin() || CurrentUserHolder.daQuaCongHaiLop()) {
            return nguoiGoi;
        }
        CurrentUser hieuLuc = congHaiLop.getObject().duocDungQuyenAdmin(nguoiGoi.id())
                ? nguoiGoi
                : nguoiGoi.haVaiTroViChuaBatHaiLop();
        CurrentUserHolder.datSauCongHaiLop(hieuLuc);
        return hieuLuc;
    }
}
