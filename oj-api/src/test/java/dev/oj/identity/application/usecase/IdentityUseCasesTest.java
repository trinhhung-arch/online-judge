package dev.oj.identity.application.usecase;

import dev.oj.identity.application.SessionIssuer;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.RefreshTokenSecret;
import dev.oj.identity.domain.UserStatus;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.JwtService;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Use-case của {@code identity} — Bước 4.3, FR-AUTH-01 → 08.
 *
 * <p>Chạy bằng fake nên cả lớp mất chưa tới một giây; xem javadoc của {@link IdentityFakes}.
 */
class IdentityUseCasesTest {

    private static final Instant BAY_GIO = Instant.parse("2026-08-29T10:00:00Z");
    private static final String IP = "203.0.113.7";

    private IdentityFakes.UsersGia users;
    private IdentityFakes.TokensGia tokens;
    private IdentityFakes.LanThuGia lanThu;
    private IdentityFakes.NhatKyGia nhatKy;
    private IdentityFakes.BamGia hasher;
    private IdentityFakes.HaiLopGia haiLop;

    /** Đếm được, và ném được — để đo cả hai nhánh của hàng rào giãn nhịp đăng nhập. */
    private final java.util.List<Long> gioiHanDaGoi = new java.util.ArrayList<>();
    private boolean gioiHanNem = false;
    private final dev.oj.identity.application.port.LoginRateLimiter gioiHanDangNhap = (id) -> {
        gioiHanDaGoi.add(id);
        if (gioiHanNem) {
            throw IdentityException.dangNhapQuaNhanh(java.time.Duration.ofSeconds(2));
        }
    };

    /**
     * Không bật 2FA cho ai trong các ca cũ, nên nó là một cổng luôn mở — trừ ca 2FA
     * riêng bên dưới, nơi test tự nạp dữ liệu vào `haiLop`.
     */
    private dev.oj.identity.application.TotpChecker totpChecker() {
        return new dev.oj.identity.application.TotpChecker(
                haiLop, new IdentityFakes.MaHoaGia(),
                java.time.Clock.fixed(BAY_GIO, java.time.ZoneOffset.UTC));
    }
    private IdentityFakes.ChanDangKyGia chanDangKy;
    private SessionIssuer phatPhien;
    private AppProperties props;

    @BeforeEach
    void dung() {
        users = new IdentityFakes.UsersGia();
        tokens = new IdentityFakes.TokensGia();
        lanThu = new IdentityFakes.LanThuGia();
        haiLop = new IdentityFakes.HaiLopGia();
        chanDangKy = new IdentityFakes.ChanDangKyGia();
        nhatKy = new IdentityFakes.NhatKyGia();
        hasher = new IdentityFakes.BamGia();
        props = IdentityFakes.properties();
        phatPhien = new SessionIssuer(
                new JwtService(props, Clock.fixed(BAY_GIO, ZoneOffset.UTC)),
                tokens, props, Clock.fixed(BAY_GIO, ZoneOffset.UTC));
    }

    private LoginUseCase dangNhap() {
        return new LoginUseCase(users, hasher, lanThu, phatPhien, props,
                Clock.fixed(BAY_GIO, ZoneOffset.UTC), totpChecker(), gioiHanDangNhap);
    }

    private long themNguoiDung(String handle, Role role) {
        return users.them(handle, handle + "@oj.test", role, UserStatus.ACTIVE,
                hasher.bam("matkhau-tot-123"));
    }

    // =========================================================================

    @Nested
    @DisplayName("FR-AUTH-01 · đăng ký")
    class DangKy {

        private RegisterUserUseCase useCase() {
            return new RegisterUserUseCase(users, hasher, nhatKy, chanDangKy, (t, ip) -> { });
        }

        @Test
        @DisplayName("★ vai trò LUÔN là USER — không có tham số nào đổi được điều đó")
        void luon_la_user() {
            long id = useCase().thucHien("nguoi-moi", "moi@oj.test", "Người mới", "matkhau-tot-123", IP, "captcha-gia");

            assertThat(users.timTheoId(id)).get()
                    .extracting("role").isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("mật khẩu được băm, không bao giờ lưu nguyên văn")
        void mat_khau_duoc_bam() {
            long id = useCase().thucHien("a-b-c", "abc@oj.test", "ABC", "matkhau-tot-123", IP, "captcha-gia");

            assertThat(users.bamMatKhau.get(id))
                    .isNotEqualTo("matkhau-tot-123")
                    .isEqualTo(hasher.bam("matkhau-tot-123"));
        }

        @Test
        @DisplayName("handle sai định dạng, email sai, mật khẩu ngắn — đều là 400 với câu riêng")
        void dau_vao_sai_thi_400() {
            var uc = useCase();
            assertThatThrownBy(() -> uc.thucHien("ab", "a@oj.test", "A", "matkhau-tot-123", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.INVALID);
            assertThatThrownBy(() -> uc.thucHien("hop-le", "khong-phai-email", "A", "matkhau-tot-123", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class);
            assertThatThrownBy(() -> uc.thucHien("hop-le", "a@oj.test", "A", "ngan", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class);
        }

        @Test
        @DisplayName("trùng handle → 409, và câu chữ nói rõ trùng cái gì")
        void trung_handle_thi_409() {
            useCase().thucHien("trung", "mot@oj.test", "Một", "matkhau-tot-123", IP, "captcha-gia");

            assertThatThrownBy(() ->
                    useCase().thucHien("TRUNG", "hai@oj.test", "Hai", "matkhau-tot-123", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("publicMessage",
                            "Tên đăng nhập này đã có người dùng. Hãy chọn Tên đăng nhập khác.");
        }

        @Test
        @DisplayName("★ FR-AUTH-01 · quá nhiều lượt từ một IP → 429, kể cả khi lượt đó HỎNG")
        void qua_nhieu_tu_mot_ip() {
            chanDangKy.toiDa = 2;
            var uc = useCase();

            uc.thucHien("nguoi-1", "mot@oj.test", "Một", "matkhau-tot-123", IP, "captcha-gia");
            // Lượt thứ hai hỏng vì handle sai định dạng — nhưng nó VẪN được đếm. Nếu không,
            // một bot chỉ cần gửi handle rác là dò được handle nào còn trống mà không tốn gì.
            assertThatThrownBy(() -> uc.thucHien("x", "hai@oj.test", "Hai", "matkhau-tot-123", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.INVALID);

            assertThatThrownBy(() ->
                    uc.thucHien("nguoi-3", "ba@oj.test", "Ba", "matkhau-tot-123", IP, "captcha-gia"))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED);
        }

        @Test
        @DisplayName("giới hạn tính THEO IP — người ở IP khác không bị vạ lây")
        void ip_khac_khong_bi_va_lay() {
            chanDangKy.toiDa = 1;
            var uc = useCase();
            uc.thucHien("nguoi-a", "a@oj.test", "A", "matkhau-tot-123", IP, "captcha-gia");

            assertThatCode(() ->
                    uc.thucHien("nguoi-b", "b@oj.test", "B", "matkhau-tot-123", "203.0.113.9",
                            "captcha-gia"))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("FR-AUTH-02 · đăng nhập")
    class DangNhap {

        @Test
        @DisplayName("đăng nhập được bằng handle hoặc bằng email, cùng một ô nhập")
        void handle_hoac_email() {
            themNguoiDung("nguoi-a", Role.USER);

            assertThat(dangNhap().thucHien("nguoi-a", "matkhau-tot-123", "curl", IP, null)).isNotNull();
            assertThat(dangNhap().thucHien("nguoi-a@oj.test", "matkhau-tot-123", "curl", IP, null))
                    .isNotNull();
        }

        @Test
        @DisplayName("★ bốn nguyên nhân thất bại cho ĐÚNG MỘT thông báo — chống dò tài khoản")
        void mot_thong_bao_cho_moi_nguyen_nhan() {
            themNguoiDung("co-that", Role.USER);
            long biKhoa = users.them("bi-khoa", "bk@oj.test", Role.USER, UserStatus.DISABLED,
                    hasher.bam("matkhau-tot-123"));
            assertThat(biKhoa).isPositive();

            String cauChung = null;
            for (String[] ca : new String[][]{
                    {"khong-ton-tai", "matkhau-tot-123"},   // handle không có
                    {"co-that", "sai-mat-khau-roi"},        // mật khẩu sai
                    {"bi-khoa", "matkhau-tot-123"}}) {      // tài khoản bị vô hiệu hoá
                try {
                    dangNhap().thucHien(ca[0], ca[1], "curl", IP, null);
                    throw new AssertionError("đáng lẽ phải ném với " + ca[0]);
                } catch (IdentityException e) {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.UNAUTHENTICATED);
                    if (cauChung == null) {
                        cauChung = e.publicMessage();
                    }
                    assertThat(e.publicMessage())
                            .describedAs("ca '%s' phải cho cùng một câu", ca[0])
                            .isEqualTo(cauChung);
                    // Và câu đó không được nhắc tới handle đã thử.
                    assertThat(e.publicMessage()).doesNotContain(ca[0]);
                }
            }
        }

        @Test
        @DisplayName("★ tài khoản không tồn tại VẪN tốn một lượt băm — chống dò bằng đồng hồ")
        void khong_ton_tai_van_bam() {
            var demBam = new java.util.concurrent.atomic.AtomicInteger();
            var hasherDem = new dev.oj.identity.application.port.PasswordHasher() {
                @Override
                public String bam(String matKhauTho) {
                    return hasher.bam(matKhauTho);
                }

                @Override
                public boolean khop(String matKhauTho, String bamDaLuu) {
                    demBam.incrementAndGet();
                    return hasher.khop(matKhauTho, bamDaLuu);
                }
            };
            var uc = new LoginUseCase(users, hasherDem, lanThu, phatPhien, props,
                    Clock.fixed(BAY_GIO, ZoneOffset.UTC), totpChecker(), gioiHanDangNhap);

            assertThatThrownBy(() -> uc.thucHien("khong-ai-ca", "matkhau-tot-123", "curl", IP, null))
                    .isInstanceOf(IdentityException.class);

            // Nếu use-case thoát sớm khi không tìm thấy thì con số này là 0, và thời gian phản
            // hồi trở thành câu trả lời cho "email này có tồn tại không".
            assertThat(demBam).hasValue(1);
        }

        @Test
        @DisplayName("FR-AUTH-08 · sai đủ 5 lần thì IP bị khoá, và lần thứ 6 trả 429")
        void khoa_sau_5_lan_sai() {
            themNguoiDung("nan-nhan", Role.USER);

            for (int i = 0; i < 5; i++) {
                assertThatThrownBy(() -> dangNhap().thucHien("nan-nhan", "sai-roi", "curl", IP, null))
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAUTHENTICATED);
            }
            assertThat(lanThu.khoaToi).isEqualTo(BAY_GIO.plus(Duration.ofMinutes(15)));

            // Lần thứ 6: khoá chặn TRƯỚC cả khi mật khẩu đúng.
            assertThatThrownBy(() -> dangNhap().thucHien("nan-nhan", "matkhau-tot-123", "curl", IP, null))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                    .hasFieldOrPropertyWithValue("retryAfter", Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("mọi lần thử đều được ghi nhận, kể cả lần thành công")
        void moi_lan_thu_deu_duoc_ghi() {
            themNguoiDung("nguoi-b", Role.USER);

            assertThatThrownBy(() -> dangNhap().thucHien("nguoi-b", "sai", "curl", IP, null))
                    .isInstanceOf(IdentityException.class);
            dangNhap().thucHien("nguoi-b", "matkhau-tot-123", "curl", IP, null);

            assertThat(lanThu.ghiNhan).containsExactly("nguoi-b:false", "nguoi-b:true");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("FR-AUTH-02 · xoay vòng refresh token")
    class XoayVong {

        private RefreshSessionUseCase useCase() {
            return new RefreshSessionUseCase(tokens, users, phatPhien, nhatKy,
                    Clock.fixed(BAY_GIO, ZoneOffset.UTC));
        }

        @Test
        @DisplayName("làm mới trả token MỚI và thu hồi token cũ")
        void xoay_vong_thu_hoi_cai_cu() {
            themNguoiDung("nguoi-c", Role.USER);
            SessionIssuer.Session cu = dangNhap().thucHien("nguoi-c", "matkhau-tot-123", "curl", IP, null);

            SessionIssuer.Session moi = useCase().thucHien(cu.refreshToken(), "curl", IP);

            assertThat(moi.refreshToken()).isNotEqualTo(cu.refreshToken());
            assertThat(tokens.timTheoBam(RefreshTokenSecret.bam(cu.refreshToken())))
                    .get().extracting("revokedAt").isNotNull();
        }

        @Test
        @DisplayName("★ token cũ dùng lại → THU HỒI TOÀN BỘ phiên, vì nó nghĩa là có bản sao")
        void dung_lai_token_cu_thi_thu_hoi_het() {
            long id = themNguoiDung("bi-trom", Role.USER);
            SessionIssuer.Session mot = dangNhap().thucHien("bi-trom", "matkhau-tot-123", "curl", IP, null);
            SessionIssuer.Session hai = useCase().thucHien(mot.refreshToken(), "curl", IP);

            // Kẻ tấn công trình lại bản đã bị thu hồi.
            assertThatThrownBy(() -> useCase().thucHien(mot.refreshToken(), "curl", IP))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("code", "identity.phien_bi_dung_lai");

            // Cả phiên đang hợp lệ của chủ tài khoản cũng chết — cố ý: không biết ai vừa
            // trình token cũ, nên phải xử lý như thể đó là kẻ tấn công.
            assertThatThrownBy(() -> useCase().thucHien(hai.refreshToken(), "curl", IP))
                    .isInstanceOf(IdentityException.class);
            assertThat(nhatKy.hanhDong).contains("REFRESH_TOKEN_REUSE_DETECTED");
            assertThat(id).isPositive();
        }

        @Test
        @DisplayName("token hết hạn, token bịa, token rỗng — đều là phiên không hợp lệ")
        void token_hong_thi_401() {
            var uc = useCase();
            for (String xau : new String[]{null, "", "  ", "bia-dat-hoan-toan"}) {
                assertThatThrownBy(() -> uc.thucHien(xau, "curl", IP))
                        .isInstanceOf(IdentityException.class)
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAUTHENTICATED);
            }
        }

        @Test
        @DisplayName("★ tài khoản bị vô hiệu hoá thì dừng ở lần làm mới kế tiếp")
        void tai_khoan_bi_khoa_thi_khong_lam_moi_duoc() {
            long id = themNguoiDung("se-bi-khoa", Role.USER);
            SessionIssuer.Session phien = dangNhap()
                    .thucHien("se-bi-khoa", "matkhau-tot-123", "curl", IP, null);

            users.anDanhHoa(id, "[đã xoá #" + id + "]");

            assertThatThrownBy(() -> useCase().thucHien(phien.refreshToken(), "curl", IP))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAUTHENTICATED);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ Giãn nhịp đăng nhập theo tài khoản")
    class GianNhipDangNhap {

        @Test
        @DisplayName("★ chỉ đếm lượt THÀNH CÔNG — nếu không thì ai cũng khoá được người khác")
        void chi_dem_luot_thanh_cong() {
            themNguoiDung("nguoi-g", Role.USER);

            assertThatThrownBy(() -> dangNhap().thucHien("nguoi-g", "sai-mat-khau", "curl", IP, null))
                    .isInstanceOf(IdentityException.class);
            assertThat(gioiHanDaGoi)
                    .as("gõ sai mật khẩu của người khác KHÔNG được chạm hàng rào của họ — "
                            + "nếu chạm thì đây là một công cụ khoá tài khoản, không phải "
                            + "một hàng rào")
                    .isEmpty();

            dangNhap().thucHien("nguoi-g", "matkhau-tot-123", "curl", IP, null);
            assertThat(gioiHanDaGoi).hasSize(1);
        }

        @Test
        @DisplayName("hàng rào chặn thì KHÔNG phát phiên")
        void chan_thi_khong_phat_phien() {
            themNguoiDung("nguoi-h", Role.USER);
            gioiHanNem = true;

            assertThatThrownBy(() -> dangNhap().thucHien("nguoi-h", "matkhau-tot-123", "curl", IP, null))
                    .isInstanceOf(IdentityException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.RATE_LIMITED)
                    .hasFieldOrPropertyWithValue("code", "identity.dang_nhap_qua_nhanh");
        }
    }

    @Nested
    @DisplayName("FR-AUTH-03 · đăng xuất")
    class DangXuat {

        @Test
        @DisplayName("thu hồi token đang dùng")
        void thu_hoi() {
            themNguoiDung("nguoi-d", Role.USER);
            var phien = dangNhap().thucHien("nguoi-d", "matkhau-tot-123", "curl", IP, null);

            new LogoutUseCase(tokens).thucHien(phien.refreshToken());

            assertThat(tokens.timTheoBam(RefreshTokenSecret.bam(phien.refreshToken())))
                    .get().extracting("revokedAt").isNotNull();
        }

        @Test
        @DisplayName("★ idempotent: token rỗng, token bịa, hai lần liên tiếp — không ném gì")
        void idempotent() {
            var uc = new LogoutUseCase(tokens);
            themNguoiDung("nguoi-e", Role.USER);
            var phien = dangNhap().thucHien("nguoi-e", "matkhau-tot-123", "curl", IP, null);

            uc.thucHien(null);
            uc.thucHien("");
            uc.thucHien("khong-ton-tai");
            uc.thucHien(phien.refreshToken());
            uc.thucHien(phien.refreshToken());
        }
    }
}
