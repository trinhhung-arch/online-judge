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
