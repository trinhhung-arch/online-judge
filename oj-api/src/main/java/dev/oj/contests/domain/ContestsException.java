package dev.oj.contests.domain;

import dev.oj.platform.error.DomainException;

/**
 * Lỗi của module {@code contests}. {@code CLAUDE.md} mục 7: mỗi module một exception riêng.
 *
 * <h2>★ {@link #khongTimThay()} là câu trả lời cho NHIỀU câu hỏi khác nhau</h2>
 * "Contest không tồn tại", "contest chưa mở", "bạn chưa đăng ký" — cả ba đều trả 404 với cùng
 * một câu. Phân biệt chúng là nói cho người ngoài biết <b>có một kỳ thi sắp diễn ra</b>, và
 * với một hệ thống mà đề của contest là thứ không được lộ trước giờ, đó là thông tin đủ để
 * bắt đầu dò.
 */
public class ContestsException extends DomainException {

    private ContestsException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static ContestsException khongTimThay() {
        return new ContestsException(Kind.NOT_FOUND, "contest.khong_tim_thay",
                "Không tìm thấy kỳ thi này.",
                "Contest không tồn tại, chưa mở, hoặc người gọi chưa đăng ký");
    }

    public static ContestsException khongHopLe(String code, String publicMessage) {
        return new ContestsException(Kind.INVALID, code, publicMessage, publicMessage);
    }

    /** FR-CON-02 — đăng ký phải xong TRƯỚC giờ bắt đầu. */
    public static ContestsException dangKyDaDong() {
        return new ContestsException(Kind.CONFLICT, "contest.dang_ky_da_dong",
                "Kỳ thi đã bắt đầu, không đăng ký được nữa.",
                "Yêu cầu đăng ký sau starts_at");
    }

    public static ContestsException daDangKy() {
        return new ContestsException(Kind.CONFLICT, "contest.da_dang_ky",
                "Bạn đã đăng ký kỳ thi này rồi.", "Chạm khoá chính contest_registrations");
    }

    /**
     * Đề bị khoá vì lịch contest — FR-CON-03.
     *
     * <p><b>404 chứ không 403.</b> 403 xác nhận đề đó tồn tại và đang thuộc một kỳ thi, và
     * chính điều đó là thứ không được lộ trước giờ thi.
     */
    public static ContestsException deBiKhoa() {
        return new ContestsException(Kind.NOT_FOUND, "contest.de_bi_khoa",
                "Không tìm thấy đề này.",
                "Đề thuộc một contest chưa mở hoặc người gọi chưa đăng ký (FR-CON-03)");
    }

    /** FR-CON-05 — chỉ công bố được sau khi thi xong. */
    public static ContestsException chuaKetThuc() {
        return new ContestsException(Kind.CONFLICT, "contest.chua_ket_thuc",
                "Kỳ thi chưa kết thúc.",
                "Yêu cầu công bố bảng xếp hạng khi contest còn đang chạy");
    }
}
