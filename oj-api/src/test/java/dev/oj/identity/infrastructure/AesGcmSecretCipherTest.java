package dev.oj.identity.infrastructure;

import dev.oj.identity.domain.Totp;
import dev.oj.platform.config.AppPropertiesGia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmSecretCipherTest {

    private final AesGcmSecretCipher cipher = new AesGcmSecretCipher(AppPropertiesGia.macDinh());

    @Test
    @DisplayName("mã hoá rồi giải mã ra đúng bí mật, và bản mã không chứa bí mật thô")
    void di_ve_nguyen_ven() {
        String biMat = Totp.sinhBiMat();

        String banMa = cipher.maHoa(biMat);

        assertThat(banMa).doesNotContain(biMat);
        assertThat(cipher.giaiMa(banMa)).isEqualTo(biMat);
    }

    @Test
    @DisplayName("★ cùng một bí mật mã hoá hai lần ra hai bản mã KHÁC nhau — IV ngẫu nhiên mỗi lần")
    void iv_ngau_nhien_moi_lan() {
        String biMat = Totp.sinhBiMat();

        String a = cipher.maHoa(biMat);
        String b = cipher.maHoa(biMat);

        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.giaiMa(a)).isEqualTo(biMat);
        assertThat(cipher.giaiMa(b)).isEqualTo(biMat);
    }

    @Test
    @DisplayName("★ sửa một byte ở IV, ở thân, ở tag → từ chối, không bao giờ trả về một bí mật sai")
    void sua_mot_byte_thi_tu_choi() {
        byte[] tho = Base64.getDecoder().decode(cipher.maHoa(Totp.sinhBiMat()));

        for (int viTri : new int[]{0, 12, tho.length - 1}) {
            byte[] sua = tho.clone();
            sua[viTri] ^= 0x01;
            String banSua = Base64.getEncoder().encodeToString(sua);

            assertThatThrownBy(() -> cipher.giaiMa(banSua))
                    .as("sửa byte %d", viTri)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("★ giải mã bằng khoá khác → từ chối, và câu lỗi chỉ đúng nguyên nhân: OJ_TOTP_KEY bị đổi")
    void khoa_khac_thi_tu_choi() {
        String biMat = Totp.sinhBiMat();
        String banMa = cipher.maHoa(biMat);
        var khoaKhac = new AesGcmSecretCipher(AppPropertiesGia.voiKhoaTotp("u".repeat(32)));

        assertThatThrownBy(() -> khoaKhac.giaiMa(banMa))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OJ_TOTP_KEY")
                .hasMessageNotContaining(biMat);
    }

    @Test
    @DisplayName("bản mã không dài hơn IV, hoặc không phải Base64 → từ chối gọn, không ném lỗi chỉ số mảng")
    void ban_ma_rac_thi_tu_choi() {
        String chiCoIv = Base64.getEncoder().encodeToString(new byte[12]);

        assertThatThrownBy(() -> cipher.giaiMa(chiCoIv)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.giaiMa("khong-phai-base64!"))
                .isInstanceOf(IllegalStateException.class);
    }
}
