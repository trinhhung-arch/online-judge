package dev.oj.identity.application.port;

import dev.oj.identity.domain.TwoFactor;

import java.util.List;
import java.util.Optional;

/** Kho trạng thái 2FA và mã dự phòng — bảng {@code user_two_factor}, {@code user_scratch_code} (V11). */
public interface TwoFactorRepository {

    Optional<TwoFactor> tim(long userId);

    /**
     * Ghi một bí mật CHƯA BẬT, ghi đè bản nháp cũ nếu có.
     *
     * <p>Ghi đè là đúng: bấm "bật 2FA" hai lần thì lần sau thắng, và bản nháp cũ không còn ý
     * nghĩa gì. Nhưng nó <b>không</b> được ghi đè một hàng đã bật — {@code WHERE enabled =
     * FALSE} lo việc đó, vì nếu không thì một request lạ sẽ thay được bí mật đang dùng thật
     * và khoá chủ tài khoản ra ngoài.
     *
     * @return {@code false} nếu người này đã bật 2FA rồi
     */
    boolean luuBanNhap(long userId, String secretEnc);

    /** Bật sau khi người dùng đã chứng minh quét được mã. */
    void bat(long userId, long buocDaDung);

    /** Chống phát lại — ghi bước vừa dùng. */
    void ghiBuoc(long userId, long buoc);

    /** Tắt hẳn: xoá cả bí mật lẫn mã dự phòng. */
    void xoa(long userId);

    /** Thay toàn bộ mã dự phòng bằng bộ mới (đã băm). */
    void thayMaDuPhong(long userId, List<String> banBam);

    /** Băm của các mã dự phòng chưa dùng, kèm id để đánh dấu. */
    List<MaDuPhong> maDuPhongChuaDung(long userId);

    void danhDauDaDung(long maDuPhongId);

    record MaDuPhong(long id, String codeHash) {
    }
}
