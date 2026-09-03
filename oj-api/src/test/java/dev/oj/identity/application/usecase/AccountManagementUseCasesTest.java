package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.platform.error.DomainException;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ba use-case <b>đổi trạng thái một tài khoản</b>: đổi mật khẩu (FR-AUTH-04), ẩn danh hoá
 * (FR-AUTH-07), và quản trị vai trò/vô hiệu hoá (FR-ADM-03, Bước 6.6).
 *
 * <h2>Vì sao tách khỏi {@code IdentityUseCasesTest}</h2>
 * Trần 300 dòng của {@code CLAUDE.md} mục 7 là lý do trực tiếp, nhưng ranh giới thì không tuỳ
 * tiện: ba nhóm ca ở đây có <b>chung một bất biến</b> mà đăng ký và đăng nhập không có —
 * <i>đổi thứ gì thuộc về một tài khoản thì phải thu hồi mọi phiên của tài khoản đó</i>.
 *
 * <p>Access token sống 15 phút và không tra database ở mỗi request; đó là cái giá đã chọn khi
 * chọn JWT. Nên "thu hồi refresh token" là toàn bộ thứ kiến trúc này cho phép, và nó biến
 * "vô hạn" thành "tối đa 15 phút". Mọi ca trong file này kiểm đúng điều đó ở một tình huống
 * khác nhau.
 */
class AccountManagementUseCasesTest {

    private static final Instant BAY_GIO = Instant.parse("2026-08-29T10:00:00Z");
    private static final String IP = "203.0.113.7";

    private IdentityFakes.UsersGia users;
    private IdentityFakes.TokensGia tokens;
    private IdentityFakes.LanThuGia lanThu;
    private IdentityFakes.NhatKyGia nhatKy;
    private IdentityFakes.BamGia hasher;
    private SessionIssuer phatPhien;
    private AppProperties props;

    @BeforeEach
    void dung() {
        users = new IdentityFakes.UsersGia();
        tokens = new IdentityFakes.TokensGia();
        lanThu = new IdentityFakes.LanThuGia();
        nhatKy = new IdentityFakes.NhatKyGia();
        hasher = new IdentityFakes.BamGia();
        props = IdentityFakes.properties();
        phatPhien = new SessionIssuer(
                new JwtService(props, Clock.fixed(BAY_GIO, ZoneOffset.UTC)),
                tokens, props, Clock.fixed(BAY_GIO, ZoneOffset.UTC));
    }

    private LoginUseCase dangNhap() {
        return new LoginUseCase(users, hasher, lanThu, phatPhien, props,
                Clock.fixed(BAY_GIO, ZoneOffset.UTC));
    }

    private long themNguoiDung(String handle, Role role) {
        return users.them(handle, handle + "@oj.test", role, UserStatus.ACTIVE,
                hasher.bam("matkhau-tot-123"));
    }


    // =========================================================================

    @Nested
    @DisplayName("FR-AUTH-04 · đổi mật khẩu")
    class DoiMatKhau {

        @Test
        @DisplayName("★ đổi xong thì MỌI phiên bị thu hồi, kể cả phiên vừa gọi")
        void thu_hoi_moi_phien() {
            long id = themNguoiDung("nguoi-f", Role.USER);
            var mot = dangNhap().thucHien("nguoi-f", "matkhau-tot-123", "curl", IP);
            var hai = dangNhap().thucHien("nguoi-f", "matkhau-tot-123", "firefox", IP);

            new ChangePasswordUseCase(IdentityFakes.nguoiGoi(id, Role.USER), users, hasher,
                    tokens, nhatKy).thucHien("matkhau-tot-123", "mat-khau-moi-456");

            for (var phien : new SessionIssuer.Session[]{mot, hai}) {
                assertThat(tokens.timTheoBam(RefreshTokenSecret.bam(phien.refreshToken())))
                        .get().extracting("revokedAt").isNotNull();
            }
            assertThat(users.bamMatKhau.get(id)).isEqualTo(hasher.bam("mat-khau-moi-456"));
            assertThat(nhatKy.hanhDong).contains("PASSWORD_CHANGED");
        }

        @Test
        @DisplayName("sai mật khẩu cũ → 400, và mật khẩu không đổi")
        void sai_mat_khau_cu() {
            long id = themNguoiDung("nguoi-g", Role.USER);

            assertThatThrownBy(() -> new ChangePasswordUseCase(
                    IdentityFakes.nguoiGoi(id, Role.USER), users, hasher, tokens, nhatKy)
                    .thucHien("khong-phai-mat-khau-cu", "mat-khau-moi-456"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("code", "identity.sai_mat_khau_cu");

            assertThat(users.bamMatKhau.get(id)).isEqualTo(hasher.bam("matkhau-tot-123"));
        }

        @Test
        @DisplayName("mật khẩu mới yếu bị từ chối TRƯỚC khi kiểm mật khẩu cũ")
        void mat_khau_moi_yeu() {
            long id = themNguoiDung("nguoi-h", Role.USER);

            assertThatThrownBy(() -> new ChangePasswordUseCase(
                    IdentityFakes.nguoiGoi(id, Role.USER), users, hasher, tokens, nhatKy)
                    .thucHien("matkhau-tot-123", "ngan"))
                    .hasFieldOrPropertyWithValue("code", "identity.mat_khau_qua_ngan");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("FR-AUTH-07 · ẩn danh hoá")
    class AnDanhHoa {

        private AnonymizeAccountUseCase useCase(long adminId) {
            return new AnonymizeAccountUseCase(IdentityFakes.nguoiGoi(adminId, Role.ADMIN),
                    users, tokens, nhatKy);
        }

        @Test
        @DisplayName("★ email và mật khẩu bị xoá thật, tên hiển thị thành [đã xoá #id]")
        void xoa_that_du_lieu_dinh_danh() {
            long admin = themNguoiDung("quan-tri", Role.ADMIN);
            long nanNhan = themNguoiDung("roi-di", Role.USER);

            useCase(admin).thucHien(nanNhan);

            assertThat(users.timTheoId(nanNhan)).get().satisfies(u -> {
                assertThat(u.email()).isNull();
                assertThat(u.status()).isEqualTo(UserStatus.ANONYMIZED);
                assertThat(u.displayName()).isEqualTo("[đã xoá #" + nanNhan + "]");
            });
            assertThat(users.bamMatKhau.get(nanNhan)).isNull();
        }

        @Test
        @DisplayName("★ audit ghi handle CŨ nhưng KHÔNG ghi email — email vừa bị xoá theo yêu cầu")
        void audit_khong_chep_lai_email() {
            long admin = themNguoiDung("quan-tri-2", Role.ADMIN);
            long nanNhan = themNguoiDung("roi-di-2", Role.USER);

            useCase(admin).thucHien(nanNhan);

            assertThat(nhatKy.hanhDong).contains("USER_ANONYMIZED");
            var chiTiet = nhatKy.chiTiet.get(nhatKy.hanhDong.indexOf("USER_ANONYMIZED"));
            assertThat(chiTiet).containsEntry("handleCu", "roi-di-2");
            assertThat(chiTiet.toString()).doesNotContain("@oj.test");
        }

        @Test
        @DisplayName("★ ADMIN không tự ẩn danh hoá mình — sẽ tự khoá quyền quản trị")
        void khong_tu_an_danh() {
            long admin = themNguoiDung("quan-tri-3", Role.ADMIN);

            assertThatThrownBy(() -> useCase(admin).thucHien(admin))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT);
        }

        @Test
        @DisplayName("gọi hai lần thì lần hai không làm gì — idempotent")
        void idempotent() {
            long admin = themNguoiDung("quan-tri-4", Role.ADMIN);
            long nanNhan = themNguoiDung("roi-di-4", Role.USER);

            useCase(admin).thucHien(nanNhan);
            useCase(admin).thucHien(nanNhan);

            assertThat(nhatKy.hanhDong).filteredOn("USER_ANONYMIZED"::equals).hasSize(1);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-ADM-03 · đổi vai trò và vô hiệu hoá — Bước 6.6")
    class QuanTriTaiKhoan {

        private ManageUserUseCase useCase(long adminId) {
            return new ManageUserUseCase(
                    () -> new CurrentUserProvider.CurrentUser(adminId, "quan-tri", Role.ADMIN),
                    users, tokens, nhatKy);
        }

        /**
         * ★ Ca quan trọng nhất của FR-ADM-03.
         *
         * <p>Hạ vai trò mà không thu hồi phiên nghĩa là người đó <b>giữ quyền cũ vô hạn</b>:
         * access token sống 15 phút và không tra database, còn refresh token thì đổi lấy một
         * access token mới mãi mãi. Thu hồi biến "vô hạn" thành "tối đa 15 phút" — và 15 phút
         * là trần không xoá được của việc chọn JWT, không phải một thiếu sót.
         */
        @Test
        @DisplayName("★ đổi vai trò thì thu hồi MỌI phiên của người đó")
        void doi_vai_tro_thi_thu_hoi_phien() {
            long admin = themNguoiDung("quan-tri-6", Role.ADMIN);
            long nanNhan = themNguoiDung("bi-ha-6", Role.SETTER);
            var phien = dangNhap().thucHien("bi-ha-6", "matkhau-tot-123", "curl", IP);

            useCase(admin).doiVaiTro(nanNhan, Role.USER);

            assertThat(users.theoId.get(nanNhan).role()).isEqualTo(Role.USER);
            assertThat(tokens.timTheoBam(RefreshTokenSecret.bam(phien.refreshToken())))
                    .get().extracting("revokedAt").isNotNull();
            assertThat(nhatKy.hanhDong).contains("USER_ROLE_CHANGED");
        }

        @Test
        @DisplayName("★ vô hiệu hoá thì thu hồi phiên VÀ chặn đăng nhập lại")
        void vo_hieu_hoa_thi_khong_dang_nhap_duoc() {
            long admin = themNguoiDung("quan-tri-7", Role.ADMIN);
            long nanNhan = themNguoiDung("gian-lan-7", Role.USER);
            var phien = dangNhap().thucHien("gian-lan-7", "matkhau-tot-123", "curl", IP);

            useCase(admin).datHoatDong(nanNhan, false);

            assertThat(users.theoId.get(nanNhan).status()).isEqualTo(UserStatus.DISABLED);
            assertThat(tokens.timTheoBam(RefreshTokenSecret.bam(phien.refreshToken())))
                    .get().extracting("revokedAt").isNotNull();
            // Chặn cả đường vào mới: Credentials.canLogIn() đọc status.
            assertThatThrownBy(() ->
                    dangNhap().thucHien("gian-lan-7", "matkhau-tot-123", "curl", IP))
                    .isInstanceOf(IdentityException.class);
        }

        @Test
        @DisplayName("mở lại được, và mở lại thì đăng nhập lại được")
        void mo_lai_duoc() {
            long admin = themNguoiDung("quan-tri-8", Role.ADMIN);
            long nanNhan = themNguoiDung("quay-lai-8", Role.USER);
            useCase(admin).datHoatDong(nanNhan, false);

            useCase(admin).datHoatDong(nanNhan, true);

            assertThat(users.theoId.get(nanNhan).status()).isEqualTo(UserStatus.ACTIVE);
            assertThat(dangNhap().thucHien("quay-lai-8", "matkhau-tot-123", "curl", IP))
                    .isNotNull();
        }

        /**
         * Hệ thống có thể còn đúng <b>một</b> ADMIN. Tự hạ vai trò hoặc tự vô hiệu hoá khi đó
         * khoá vĩnh viễn mọi đường quản trị — không có "quên mật khẩu" nào lấy lại được một
         * vai trò. Cùng lập luận với {@code AnonymizeAccountUseCase}.
         */
        @Test
        @DisplayName("★ ADMIN không tự hạ vai trò và không tự vô hiệu hoá mình")
        void khong_tu_thao_tac_voi_minh() {
            long admin = themNguoiDung("quan-tri-9", Role.ADMIN);

            assertThatThrownBy(() -> useCase(admin).doiVaiTro(admin, Role.USER))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT);
            assertThatThrownBy(() -> useCase(admin).datHoatDong(admin, false))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT);

            assertThat(users.theoId.get(admin).role()).isEqualTo(Role.ADMIN);
            assertThat(users.theoId.get(admin).status()).isEqualTo(UserStatus.ACTIVE);
        }

        /**
         * Tài khoản đã ẩn danh hoá không nhận lại được vai trò nào. Điều kiện nằm trong câu
         * {@code UPDATE} chứ không phải một câu {@code if} ở use-case — nên một đường ghi thứ
         * hai sau này cũng không đi vòng qua được.
         */
        @Test
        @DisplayName("tài khoản đã ẩn danh hoá thì không đổi vai trò được")
        void da_an_danh_thi_khong_doi_duoc() {
            long admin = themNguoiDung("quan-tri-10", Role.ADMIN);
            long nanNhan = themNguoiDung("da-di-10", Role.USER);
            new AnonymizeAccountUseCase(
                    () -> new CurrentUserProvider.CurrentUser(admin, "quan-tri", Role.ADMIN),
                    users, tokens, nhatKy).thucHien(nanNhan);

            assertThatThrownBy(() -> useCase(admin).doiVaiTro(nanNhan, Role.SETTER))
                    .isInstanceOf(IdentityException.class);
        }

        @Test
        @DisplayName("người dùng không tồn tại thì 404")
        void khong_ton_tai() {
            long admin = themNguoiDung("quan-tri-11", Role.ADMIN);

            assertThatThrownBy(() -> useCase(admin).doiVaiTro(9999L, Role.SETTER))
                    .isInstanceOf(IdentityException.class);
        }
    }
}
