package dev.oj.identity.infrastructure;

import dev.oj.identity.application.port.SecretCipher;
import dev.oj.platform.config.AppProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM. Chỉ JDK, không thư viện nào — cùng lựa chọn với {@code Jwt} và {@link
 * dev.oj.identity.domain.Totp}.
 *
 * <h2>★ GCM chứ không phải CBC, và lý do không phải là tốc độ</h2>
 * GCM là AEAD: nó vừa giấu nội dung vừa <b>phát hiện sửa đổi</b>. Với CBC, ai sửa được một
 * byte trong database sẽ đổi được bản rõ theo cách đoán trước được mà không ai biết. Với
 * GCM, mọi sửa đổi làm {@code doFinal} ném — và một bí mật TOTP bị sửa lặng lẽ nghĩa là
 * người quản trị không bao giờ đăng nhập lại được mà không ai hiểu vì sao.
 *
 * <h2>★ IV NGẪU NHIÊN MỖI LẦN, và với GCM đây là điều kiện sống còn</h2>
 * Dùng lại một cặp (khoá, IV) trong GCM không chỉ làm lộ quan hệ giữa hai bản rõ như CBC —
 * nó làm <b>lộ khoá xác thực</b>, tức là kẻ tấn công tự tạo được bản mã hợp lệ. 12 byte từ
 * {@link SecureRandom} mỗi lần gọi, và IV đi kèm bản mã vì nó không cần bí mật.
 *
 * <h2>Khoá lấy từ đâu</h2>
 * {@code SHA-256(OJ_TOTP_KEY)} — AES-256 cần đúng 32 byte, mà biến môi trường là chuỗi người
 * gõ. Băm cho ra đúng độ dài từ một chuỗi bất kỳ.
 *
 * <p><b>Khoá RIÊNG, không dùng chung với {@code OJ_JWT_SECRET}.</b> Một khoá một việc: xoay
 * khoá ký token là việc nên làm định kỳ, và nếu hai thứ dùng chung thì mỗi lần xoay sẽ làm
 * hỏng đăng ký 2FA của tất cả mọi người — biến một thao tác vệ sinh thành một sự cố.
 */
@Component
public class AesGcmSecretCipher implements SecretCipher {

    private static final String PHEP_BIEN_DOI = "AES/GCM/NoPadding";
    private static final int IV_BYTE = 12;
    private static final int TAG_BIT = 128;

    private final SecretKeySpec khoa;
    private final SecureRandom ngauNhien = new SecureRandom();

    public AesGcmSecretCipher(AppProperties properties) {
        this.khoa = new SecretKeySpec(bam(properties.auth().totpKey()), "AES");
    }

    @Override
    public String maHoa(String roThô) {
        try {
            byte[] iv = new byte[IV_BYTE];
            ngauNhien.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(PHEP_BIEN_DOI);
            cipher.init(Cipher.ENCRYPT_MODE, khoa, new GCMParameterSpec(TAG_BIT, iv));
            byte[] banMa = cipher.doFinal(roThô.getBytes(StandardCharsets.UTF_8));

            byte[] ra = new byte[iv.length + banMa.length];
            System.arraycopy(iv, 0, ra, 0, iv.length);
            System.arraycopy(banMa, 0, ra, iv.length, banMa.length);
            return Base64.getEncoder().encodeToString(ra);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Không mã hoá được bí mật TOTP", e);
        }
    }

    @Override
    public String giaiMa(String daMaHoa) {
        try {
            byte[] tat = Base64.getDecoder().decode(daMaHoa);
            if (tat.length <= IV_BYTE) {
                throw new IllegalStateException("Bản mã TOTP quá ngắn");
            }
            Cipher cipher = Cipher.getInstance(PHEP_BIEN_DOI);
            cipher.init(Cipher.DECRYPT_MODE, khoa,
                    new GCMParameterSpec(TAG_BIT, Arrays.copyOf(tat, IV_BYTE)));
            byte[] ro = cipher.doFinal(tat, IV_BYTE, tat.length - IV_BYTE);
            return new String(ro, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // KHÔNG kèm bản mã vào thông báo (bất biến #9). Nguyên nhân gần như luôn là
            // OJ_TOTP_KEY đã đổi — và lúc ấy mọi đăng ký 2FA cũ đều không giải mã được.
            throw new IllegalStateException(
                    "Không giải mã được bí mật TOTP. OJ_TOTP_KEY có bị đổi không?", e);
        }
    }

    private static byte[] bam(String chuoi) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(chuoi.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JVM không dùng được SHA-256", e);
        }
    }
}
