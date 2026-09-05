package dev.oj.identity.application;

import dev.oj.identity.application.port.PasswordHasher;
import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.Totp;
import dev.oj.identity.domain.TwoFactor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;

/**
 * Kiểm yếu tố thứ hai. <b>Không phải use-case</b> — nó không có lối vào riêng và không tự
 * quyết định quyền; nó là phần chung của {@code LoginUseCase} và
 * {@code ConfirmTwoFactorUseCase}, hai chỗ đã tự tuyên bố lập trường phân quyền của mình.
 *
 * <h2>★ Thứ tự thử: TOTP trước, mã dự phòng sau</h2>
 * Mã dự phòng chỉ dùng được một lần. Thử nó trước nghĩa là một mã TOTP hợp lệ vẫn có thể
 * đốt mất một mã dự phòng nếu chúng tình cờ trùng định dạng — hiếm, nhưng hậu quả là người
 * dùng mất dần mã cứu hộ mà không hiểu vì sao.
 *
 * <h2>★ Chống phát lại nằm ở ĐÂY, không nằm trong {@link Totp}</h2>
 * {@link Totp} là domain thuần và không có trạng thái. Nó trả về SỐ BƯỚC khớp; việc đối
 * chiếu bước ấy với {@code last_step} và ghi lại là việc của class này. Bỏ bước ấy đi thì
 * mọi ca test TOTP vẫn xanh, và cùng một mã dùng lại được suốt 30 giây.
 */
@Component
public class TotpChecker {

    private final TwoFactorRepository repository;
    private final SecretCipher cipher;
    private final PasswordHasher hasher;
    private final Clock clock;

    public TotpChecker(TwoFactorRepository repository, SecretCipher cipher,
                       PasswordHasher hasher, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.hasher = hasher;
        this.clock = clock;
    }

    /** @return true nếu tài khoản này đang bật 2FA */
    public boolean dangBat(long userId) {
        return repository.tim(userId).filter(TwoFactor::enabled).isPresent();
    }

    /**
     * @param ma mã 6 chữ số hoặc một mã dự phòng; {@code null} khi client chưa gửi
     * @throws IdentityException {@code can_totp} nếu bật 2FA mà thiếu mã;
     *                           {@code totp_sai} nếu mã sai, hết hạn hoặc đã dùng
     */
    public void kiem(long userId, String ma) {
        Optional<TwoFactor> co = repository.tim(userId).filter(TwoFactor::enabled);
        if (co.isEmpty()) {
            return;                       // không bật 2FA thì không có gì để kiểm
        }
        if (ma == null || ma.isBlank()) {
            throw IdentityException.canTotp();
        }
        TwoFactor tf = co.get();
        String gon = ma.trim();

        Long buoc = Totp.kiem(cipher.giaiMa(tf.secretEnc()), gon,
                clock.instant().getEpochSecond());
        if (buoc != null) {
            if (tf.buocDaDung(buoc)) {
                // Mã đúng nhưng bước này đã dùng — đây chính là một lần phát lại.
                throw IdentityException.totpSai();
            }
            repository.ghiBuoc(userId, buoc);
            return;
        }

        for (TwoFactorRepository.MaDuPhong m : repository.maDuPhongChuaDung(userId)) {
            if (hasher.khop(gon, m.codeHash())) {
                repository.danhDauDaDung(m.id());
                return;
            }
        }
        throw IdentityException.totpSai();
    }
}
