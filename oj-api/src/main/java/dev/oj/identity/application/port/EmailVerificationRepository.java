package dev.oj.identity.application.port;

import java.time.Instant;
import java.util.Optional;

/**
 * Cổng ra bảng {@code email_verifications} (V13) — FR-AUTH-09.
 *
 * <h2>★ Bảng này giữ ba điều kiện an toàn của một mã 6 chữ số, và cả ba nằm TRONG SQL</h2>
 * Đọc phần đầu {@code V13__xac_minh_email.sql}: mã ngắn chỉ an toàn nhờ giới hạn số lần
 * thử, hạn dùng, và việc mã mới huỷ mã cũ. Mỗi phương thức dưới đây mang một phần của ba
 * điều kiện ấy vào câu lệnh chứ không để ở tầng Java — cùng lý do với
 * {@code UserRepository.doiVaiTro}: một đường ghi thứ hai sau này sẽ quên câu {@code if},
 * nhưng không quên được câu query.
 */
public interface EmailVerificationRepository {

    /**
     * Mã đang sống của một người: chưa dùng, chưa khai tử, <b>và chưa hết hạn</b>.
     *
     * <p>Điều kiện hết hạn nằm trong SQL ({@code expires_at > now()}), nên phía gọi không
     * phải so mốc thời gian và không thể so sai múi giờ.
     */
    Optional<MaDangSong> timMaDangSong(long userId);

    /**
     * Khai tử mọi mã chưa dùng của một người, kể cả mã đã hết hạn.
     *
     * <p><b>Phải gọi trước {@link #luu}</b> — {@code ux_email_verifications_song} chỉ cho
     * phép một mã sống mỗi người, và mã hết hạn vẫn chiếm chỗ trong index đó cho tới khi
     * bị khai tử. Bỏ bước này thì lần gửi lại thứ hai đâm vào unique index.
     *
     * @return số dòng vừa khai tử
     */
    int huyMaCu(long userId);

    /**
     * @return {@code id} của dòng vừa tạo
     * @throws dev.oj.identity.domain.IdentityException {@code identity.gui_lai_qua_nhanh} nếu
     *         một request song song vừa chèn một mã cho cùng người dùng.
     *         {@code ux_email_verifications_song} là chốt thật, và nó phải được dịch thành
     *         một câu người dùng đọc được — hai cú bấm "gửi lại" trong cùng một phần trăm
     *         giây là chuyện xảy ra được, và nó không đáng nhận một lỗi 500
     */
    long luu(long userId, String maSha256, Instant hetHan);

    /**
     * Tiêu thụ một mã: đánh dấu đã dùng, <b>nếu nó vẫn còn sống ngay lúc này</b>.
     *
     * <p>Kiểm-rồi-ghi trong <i>một</i> câu lệnh, không phải đọc rồi ghi ở Java. Hai request
     * cùng trình một mã đúng thì request sau chờ khoá dòng, đánh giá lại mệnh đề trên giá
     * trị request trước vừa ghi, và đổi được 0 dòng — cùng cơ chế với
     * {@code JdbcTwoFactorRepository.ghiBuoc}.
     *
     * @return {@code false} nếu mã đã bị tiêu thụ hoặc hết hạn trong lúc đó
     */
    boolean tieuThu(long id);

    /**
     * Ghi nhận một lần gõ sai.
     *
     * @return số lần đã thử SAU khi tăng — phía gọi so với ngưỡng rồi quyết định khai tử
     */
    int ghiNhanThuSai(long id);

    /**
     * @param id        {@code email_verifications.id}
     * @param sha256Hex bản băm đã lưu, để so với thứ người dùng gõ
     * @param taoLuc    dùng cho khoảng chờ giữa hai lần gửi
     * @param soLanThu  đã gõ sai mấy lần
     */
    record MaDangSong(long id, String sha256Hex, Instant taoLuc, int soLanThu) {
    }
}
