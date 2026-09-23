package dev.oj.it;

import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.domain.Totp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Rà soát bảo mật 2026-09-24 · F2 — chống dò qua IPv6, qua HTTP và Postgres thật.
 *
 * <p>Đo phần unit test không chạm tới: SQL {@code <<=}/{@code >>=} trên kiểu {@code inet} của
 * khoá đăng nhập, câu {@code UPDATE … RETURNING} của trần mã hai lớp (V15), và việc
 * {@code ClientIp} đọc địa chỉ từ {@code CF-Connecting-IP} khi request tới từ loopback — đúng
 * đường của Cloudflare Tunnel.
 */
class ChongDoQuaIpv6HttpIT extends HttpIT {

    /** Hạt giống RFC 6238 — cùng chuỗi với IdentityHttpIT. */
    private static final String BI_MAT = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Autowired
    SecretCipher cipher;

    private ResponseEntity<Map<String, Object>> dangNhap(String ip, String matKhau, String ma) {
        var than = new HashMap<String, Object>(Map.of("dinhDanh", "dev", "password", matKhau));
        if (ma != null) {
            than.put("maHaiLop", ma);
        }
        return http.post().uri("/api/v1/auth/login")
                .header("CF-Connecting-IP", ip)
                .body(than)
                .exchange((req, res) -> ResponseEntity.status(res.getStatusCode())
                        .body(res.bodyTo(THAN_JSON)), false);
    }

    @Test
    @DisplayName("★ FR-AUTH-08 theo DẢI: 5 lần sai từ 5 địa chỉ cùng /64 → địa chỉ thứ 6 cùng dải bị khoá, dải khác thì không")
    void khoa_dang_nhap_theo_dai_64() {
        for (int i = 1; i <= 5; i++) {
            assertThat(dangNhap("2001:db8:aa:1::" + i, "sai-mat-khau", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        var cungDai = dangNhap("2001:db8:aa:1:ffff::6", MAT_KHAU_DEV, null);
        var khacDai = dangNhap("2001:db8:aa:2::1", MAT_KHAU_DEV, null);

        assertThat(cungDai.getStatusCode())
                .as("đổi địa chỉ trong cùng /64 không được thoát khoá — đó chính là đường dò cũ")
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(cungDai.getBody()).containsEntry("code", "identity.khoa_tam");
        assertThat(khacDai.getStatusCode()).as("dải khác là người khác").isEqualTo(HttpStatus.OK);
        assertThat(jdbc.sql("SELECT count(DISTINCT client_ip) FROM login_attempts WHERE NOT succeeded")
                .query(Integer.class).single())
                .as("nhật ký vẫn giữ địa chỉ ĐẦY ĐỦ, không phải dải").isEqualTo(5);
    }

    @Test
    @DisplayName("khoá ghi theo MỘT địa chỉ trước khi đổi sang dải vẫn còn hiệu lực (>>=)")
    void khoa_cu_theo_dia_chi_van_hieu_luc() {
        jdbc.sql("""
                INSERT INTO login_lockouts (client_ip, locked_until, reason)
                VALUES (CAST('2001:db8:bb:1::5' AS inet), now() + interval '10 minutes', 'khoá cũ')
                """).update();

        assertThat(dangNhap("2001:db8:bb:1::5", MAT_KHAU_DEV, null).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /**
     * Mỗi lần thử từ một /64 KHÁC — nên trần theo IP không bao giờ chạm (1 lần sai mỗi dải).
     * Chỉ trần theo tài khoản còn giữ được, và đó đúng là điều ca này đo.
     */
    @Test
    @DisplayName("★ V15: 10 mã hai lớp sai từ 10 dải khác nhau → khoá TÀI KHOẢN; mã đúng từ dải mới cũng 429")
    void tran_ma_hai_lop_theo_tai_khoan() {
        batHaiLopChoDev();
        for (int i = 1; i <= 10; i++) {
            var res = dangNhap("2001:db8:c:" + i + "::1", MAT_KHAU_DEV, maSai());
            assertThat(res.getBody()).as("lần %d", i).containsEntry("code", "identity.totp_sai");
        }

        var maDung = dangNhap("2001:db8:c:99::1", MAT_KHAU_DEV, maBayGio());

        assertThat(maDung.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(maDung.getBody()).containsEntry("code", "identity.hai_lop_tam_khoa");
        assertThat(jdbc.sql("SELECT locked_until > now() FROM user_two_factor WHERE user_id = :id")
                .param("id", USER_ID).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'TWO_FACTOR_LOCKED'")
                .query(Integer.class).single())
                .as("người trực phải thấy: có người đã gõ đúng mật khẩu rồi dò mã").isEqualTo(1);
    }

    /**
     * ★ Đường "tắt 2FA" chạy trong {@code @Transactional}: mã sai ném ngoại lệ, và thiếu
     * {@code noRollbackFor} thì lần ghi đếm cuộn lại cùng nó — ai đã có phiên + mật khẩu dò mã
     * ở đây không giới hạn. Chỉ transaction THẬT mới chứng minh được điều ngược lại.
     */
    @Test
    @DisplayName("★ mã sai ở đường TẮT 2FA cũng được đếm — không bị cuộn lại cùng ngoại lệ")
    void duong_tat_hai_lop_cung_dem() {
        batHaiLopChoDev();
        String token = bearerDev();
        for (int i = 1; i <= 10; i++) {
            http.method(org.springframework.http.HttpMethod.DELETE).uri("/api/v1/me/2fa")
                    .header("Authorization", token)
                    .body(Map.of("password", MAT_KHAU_DEV, "ma", maSai()))
                    .exchange((req, res) -> res.getStatusCode(), false);
        }

        assertThat(jdbc.sql("SELECT locked_until > now() FROM user_two_factor WHERE user_id = :id")
                .param("id", USER_ID).query(Boolean.class).optional())
                .as("10 mã sai qua đường tắt 2FA phải khoá như qua đường đăng nhập")
                .contains(true);
    }

    private void batHaiLopChoDev() {
        jdbc.sql("""
                INSERT INTO user_two_factor (user_id, secret_enc, enabled, confirmed_at)
                VALUES (:id, :bi, TRUE, now())
                ON CONFLICT (user_id) DO UPDATE
                   SET secret_enc = EXCLUDED.secret_enc, enabled = TRUE,
                       last_step = NULL, confirmed_at = now()
                """)
                .param("id", USER_ID).param("bi", cipher.maHoa(BI_MAT)).update();
    }

    /** Mã mà cả cửa sổ ±1 bước đều từ chối — không để may rủi quyết ca "mã sai". */
    private static String maSai() {
        long giay = Instant.now().getEpochSecond();
        for (int i = 0; i < 1_000_000; i++) {
            String ma = String.format("%06d", i);
            if (Totp.kiem(BI_MAT, ma, giay) == null && Totp.kiem(BI_MAT, ma, giay + 30) == null) {
                return ma;
            }
        }
        throw new AssertionError("không tìm được mã sai");
    }

    private static String maBayGio() {
        long giay = Instant.now().getEpochSecond();
        for (int i = 0; i < 1_000_000; i++) {
            String ma = String.format("%06d", i);
            Long buoc = Totp.kiem(BI_MAT, ma, giay);
            if (buoc != null && buoc == Math.floorDiv(giay, Totp.BUOC_GIAY)) {
                return ma;
            }
        }
        throw new AssertionError("không dò được mã");
    }
}
