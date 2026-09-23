package dev.oj.identity.application;

import dev.oj.identity.application.port.SecretCipher;
import dev.oj.identity.application.port.TwoFactorRepository;
import dev.oj.identity.domain.IdentityException;
import dev.oj.identity.domain.MaDuPhong;
import dev.oj.identity.domain.Totp;
import dev.oj.identity.domain.TwoFactor;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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
 *
 * <h2>★ "Đối chiếu rồi ghi" phải là MỘT câu lệnh</h2>
 * Bản đầu đọc {@code last_step}, so trong Java, rồi ghi vô điều kiện. Tám request song song
 * với cùng một mã thì cả tám cùng qua — tương tự với mã dự phòng (đo 2026-09-16,
 * {@code ChongPhatLaiHaiLopIT}). Kẻ đứng giữa bắt được một mã là dùng lại được nó ngay trong
 * 30 giây ấy, chỉ cần gửi song song với chủ tài khoản.
 *
 * <p>Giờ {@link TwoFactorRepository#ghiBuoc} và {@link TwoFactorRepository#danhDauDaDung} là
 * các câu {@code UPDATE} có điều kiện và trả về việc chính lời gọi này có ghi được không; khoá
 * dòng của Postgres bảo đảm chỉ một request thắng.
 *
 * <h2>★ Trần theo TÀI KHOẢN — V15, rà soát 2026-09-24 (F2)</h2>
 * {@code oj.auth.totp-max-failures} mã sai LIÊN TIẾP thì mọi mã của tài khoản ấy — kể cả mã
 * đúng — bị từ chối trong {@code oj.auth.totp-lockout}. Khoá được kiểm TRƯỚC khi so mã: kiểm
 * sau thì kẻ dò vẫn biết mình đoán trúng lúc đang bị khoá, và cứ thế dò tiếp. Trần theo IP của
 * FR-AUTH-08 không đủ ở bước này: người đã có mật khẩu xoay được địa chỉ, không xoay được tài
 * khoản đích. Và nó không trao ai cái nút khoá người khác — tới được đây là đã có mật khẩu.
 *
 * <p>Phát lại một mã đã dùng cũng tính là một lần sai: đó chính là tín hiệu tấn công.
 */
@Component
public class TotpChecker {

    private static final Logger log = LoggerFactory.getLogger(TotpChecker.class);

    private final TwoFactorRepository repository;
    private final SecretCipher cipher;
    private final AppProperties properties;
    private final AuditLog auditLog;
    private final Clock clock;

    public TotpChecker(TwoFactorRepository repository, SecretCipher cipher, AppProperties properties,
                       AuditLog auditLog, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** @return true nếu tài khoản này đang bật 2FA */
    public boolean dangBat(long userId) {
        return repository.tim(userId).filter(TwoFactor::enabled).isPresent();
    }

    /**
     * @param ma mã 6 chữ số hoặc một mã dự phòng; {@code null} khi client chưa gửi
     * @throws IdentityException {@code can_totp} nếu bật 2FA mà thiếu mã;
     *                           {@code hai_lop_tam_khoa} nếu tài khoản đang bị khoá bước này;
     *                           {@code totp_sai} nếu mã sai, hết hạn hoặc đã dùng
     */
    public void kiem(long userId, String ma) {
        Optional<TwoFactor> co = repository.tim(userId).filter(TwoFactor::enabled);
        if (co.isEmpty()) {
            return;                       // không bật 2FA thì không có gì để kiểm
        }
        if (ma == null || ma.isBlank()) {
            throw IdentityException.canTotp();   // bước bình thường của luồng — KHÔNG đếm
        }
        Instant bayGio = clock.instant();
        repository.khoaHaiLopToi(userId).filter(toi -> toi.isAfter(bayGio)).ifPresent(toi -> {
            throw IdentityException.haiLopTamKhoa(Duration.between(bayGio, toi));
        });
        if (dung(userId, co.get(), ma.trim())) {
            repository.xoaDemMaSai(userId);
            return;
        }
        ghiSai(userId, bayGio);
        throw IdentityException.totpSai();
    }

    private void ghiSai(long userId, Instant bayGio) {
        var auth = properties.auth();
        Instant toi = bayGio.plus(auth.totpLockout());
        if (repository.ghiMaSai(userId, auth.totpMaxFailures(), toi)) {
            // WARN + audit_log: một tài khoản có người gõ đúng mật khẩu rồi dò mã hai lớp là sự
            // kiện người trực phải thấy — mật khẩu của tài khoản ấy nhiều khả năng đã lộ.
            log.warn("Khoá bước hai lớp của user {} tới {} — {} mã sai liên tiếp",
                    userId, toi, auth.totpMaxFailures());
            auditLog.ghi("TWO_FACTOR_LOCKED", "user", userId,
                    Map.of("toi", toi.toString(), "soMaSai", auth.totpMaxFailures()));
        }
    }

    /** Mã có đúng không — TOTP trước, mã dự phòng sau (xem javadoc của class). */
    private boolean dung(long userId, TwoFactor tf, String gon) {
        Long buoc = Totp.kiem(cipher.giaiMa(tf.secretEnc()), gon,
                clock.instant().getEpochSecond());
        if (buoc != null) {
            // Mã đúng nhưng bước này đã dùng — đây chính là một lần phát lại. Phép kiểm trên
            // bản đã đọc chỉ bắt được lần phát lại TUẦN TỰ; lần phát lại SONG SONG thì mọi
            // request cùng thấy last_step cũ, nên chốt thật là câu ghi có điều kiện ngay sau.
            return !tf.buocDaDung(buoc) && repository.ghiBuoc(userId, buoc);
        }

        for (TwoFactorRepository.MaDuPhong m : repository.maDuPhongChuaDung(userId)) {
            if (MaDuPhong.khop(gon, m.codeHash())) {
                // Khớp nhưng không đánh dấu được = một request khác vừa tiêu mã này trước.
                return repository.danhDauDaDung(m.id());
            }
        }
        return false;
    }
}
