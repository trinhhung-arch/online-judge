package dev.oj.platform.security;

import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Hạ vai trò ADMIN chưa bật 2FA — cơ chế, đo bằng một cổng giả biết đếm.
 *
 * <p>Bằng chứng rằng mọi chỗ {@code isAdmin()} thật sự nhận vai trò đã hạ nằm ở hai IT
 * {@code HaiLopKhongDiVongIT} và {@code HaiLopKhongDiVongKyThiIT}; lớp này chỉ ghim ba tính
 * chất mà IT không đo được rẻ: hạ về ĐÚNG vai trò nào, hỏi cổng MẤY lần, và hỏi AI.
 */
class JwtCurrentUserProviderTest {

    /** Cổng giả: trả lời cố định và đếm số lần bị hỏi. */
    private static final class CongGia implements TwoFactorGate {
        private final boolean choQua;
        private int soLanHoi;

        CongGia(boolean choQua) {
            this.choQua = choQua;
        }

        @Override
        public boolean duocDungQuyenAdmin(long userId) {
            soLanHoi++;
            return choQua;
        }
    }

    private static JwtCurrentUserProvider provider(CongGia cong) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("congHaiLop", cong);
        return new JwtCurrentUserProvider(beans.getBeanProvider(TwoFactorGate.class));
    }

    @AfterEach
    void xoaDanhTinh() {
        CurrentUserHolder.xoa();
    }

    @Test
    @DisplayName("★ ADMIN chưa bật 2FA → SETTER, mang cờ, và isAdmin() = false")
    void admin_chua_bat_bi_ha() {
        CurrentUserHolder.dat(new CurrentUser(3L, "admin", Role.ADMIN));

        CurrentUser hieuLuc = provider(new CongGia(false)).current();

        assertThat(hieuLuc.role())
                .as("SETTER chứ không phải USER: soạn đề của chính mình vẫn là việc SETTER nào cũng làm")
                .isEqualTo(Role.SETTER);
        assertThat(hieuLuc.isAdmin()).isFalse();
        assertThat(hieuLuc.adminChuaBatHaiLop()).isTrue();
        assertThat(hieuLuc.id()).isEqualTo(3L);
    }

    @Test
    @DisplayName("ADMIN đã bật 2FA → giữ nguyên ADMIN, không mang cờ")
    void admin_da_bat_giu_nguyen() {
        CurrentUserHolder.dat(new CurrentUser(3L, "admin", Role.ADMIN));

        CurrentUser hieuLuc = provider(new CongGia(true)).current();

        assertThat(hieuLuc.isAdmin()).isTrue();
        assertThat(hieuLuc.adminChuaBatHaiLop()).isFalse();
    }

    @Test
    @DisplayName("★ hỏi cổng ĐÚNG MỘT lần mỗi request, dù current() bị gọi bao nhiêu lần")
    void hoi_mot_lan_moi_request() {
        for (boolean choQua : new boolean[] {true, false}) {
            CongGia cong = new CongGia(choQua);
            var p = provider(cong);
            CurrentUserHolder.dat(new CurrentUser(3L, "admin", Role.ADMIN));

            Role lan1 = p.current().role();
            Role lan2 = p.current().role();
            Role lan3 = p.current().role();

            assertThat(cong.soLanHoi).as("choQua=%s", choQua).isEqualTo(1);
            assertThat(lan2).isEqualTo(lan1);
            assertThat(lan3).isEqualTo(lan1);
        }
    }

    @Test
    @DisplayName("request MỚI thì hỏi lại — vừa bật 2FA xong là có quyền ngay request kế tiếp")
    void request_moi_hoi_lai() {
        CongGia cong = new CongGia(false);
        var p = provider(cong);

        CurrentUserHolder.dat(new CurrentUser(3L, "admin", Role.ADMIN));
        p.current();
        CurrentUserHolder.dat(new CurrentUser(3L, "admin", Role.ADMIN));   // JwtAuthFilter, request sau
        p.current();

        assertThat(cong.soLanHoi).isEqualTo(2);
    }

    @Test
    @DisplayName("USER và SETTER không bao giờ tốn một lượt đọc cổng")
    void khong_phai_admin_khong_hoi() {
        CongGia cong = new CongGia(false);
        var p = provider(cong);

        for (Role r : new Role[] {Role.USER, Role.SETTER}) {
            CurrentUserHolder.dat(new CurrentUser(1L, "dev", r));
            assertThat(p.current().role()).isEqualTo(r);
        }
        assertThat(cong.soLanHoi).isZero();
    }

    @Test
    @DisplayName("chưa đăng nhập → 401 như cũ, cổng không bị hỏi")
    void chua_dang_nhap() {
        CongGia cong = new CongGia(true);

        assertThatThrownBy(() -> provider(cong).current())
                .hasFieldOrPropertyWithValue("code", "auth.chua_dang_nhap");
        assertThat(cong.soLanHoi).isZero();
    }

    @Test
    @DisplayName("cờ 'chưa bật 2FA' trên một ADMIN là mâu thuẫn — không dựng được")
    void co_chi_di_voi_vai_tro_da_ha() {
        assertThatThrownBy(() -> new CurrentUser(3L, "admin", Role.ADMIN, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
