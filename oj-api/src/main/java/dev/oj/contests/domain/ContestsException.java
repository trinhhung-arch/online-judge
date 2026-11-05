package dev.oj.contests.domain;

import dev.oj.platform.error.DomainException;

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

    public static ContestsException dangKyDaDong() {
        return new ContestsException(Kind.CONFLICT, "contest.dang_ky_da_dong",
                "Kỳ thi đã bắt đầu, không đăng ký được nữa.",
                "Yêu cầu đăng ký sau starts_at");
    }

    public static ContestsException daDangKy() {
        return new ContestsException(Kind.CONFLICT, "contest.da_dang_ky",
                "Bạn đã đăng ký kỳ thi này rồi.", "Chạm khoá chính contest_registrations");
    }

    public static ContestsException deBiKhoa() {
        return new ContestsException(Kind.NOT_FOUND, "contest.de_bi_khoa",
                "Không tìm thấy đề này.",
                "Đề thuộc một contest chưa mở hoặc người gọi chưa đăng ký (FR-CON-03)");
    }

    public static ContestsException chuaKetThuc() {
        return new ContestsException(Kind.CONFLICT, "contest.chua_ket_thuc",
                "Kỳ thi chưa kết thúc.",
                "Yêu cầu công bố bảng xếp hạng khi contest còn đang chạy");
    }
}
