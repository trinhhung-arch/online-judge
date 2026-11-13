package dev.oj.judging.domain;

import dev.oj.platform.error.DomainException;

public class JudgingException extends DomainException {

    protected JudgingException(Kind kind, String code, String publicMessage, String logMessage,
                               java.time.Duration retryAfter) {
        super(kind, code, publicMessage, logMessage, retryAfter, null);
    }

    protected JudgingException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    public static JudgingException sourceTooLarge(int actualBytes) {
        return new JudgingException(
                Kind.INVALID,
                "submission.source_too_large",
                "Mã nguồn dài " + actualBytes + " byte, vượt giới hạn "
                        + DomainRules.MAX_SOURCE_BYTES + " byte (64 KB).",
                "source " + actualBytes + " byte > " + DomainRules.MAX_SOURCE_BYTES);
    }

    public static JudgingException nopQuaNhanh(java.time.Duration conLai) {
        long giay = Math.max(1, conLai.toSeconds() + (conLai.toMillisPart() > 0 ? 1 : 0));
        return new JudgingException(Kind.RATE_LIMITED, "submission.rate_limited",
                "Bạn vừa nộp bài. Chờ " + giay + " giây nữa rồi thử lại.",
                "Chạm rate limit nộp bài (FR-SUB-08)", conLai);
    }

    public static JudgingException emptySource() {
        return new JudgingException(
                Kind.INVALID,
                "submission.empty_source",
                "Mã nguồn không được để trống.",
                "source rỗng hoặc chỉ có khoảng trắng");
    }

    public static JudgingException submissionNotFound(long id) {
        return new JudgingException(
                Kind.NOT_FOUND,
                "submission.not_found",
                "Không tìm thấy bài nộp này.",
                "submission id=" + id + " không tồn tại, hoặc người gọi không được phép thấy");
    }

    public static JudgingException trangThaiKhongKhop(long submissionId, int attempt) {
        return new JudgingException(
                Kind.CONFLICT,
                "submission.trang_thai_khong_khop",
                "Bài nộp không còn ở lượt chấm này. Kết quả bị bỏ, bài sẽ được chấm lại.",
                "markDone cập nhật 0 dòng cho submission " + submissionId + " attempt " + attempt
                        + " dù khoá lạc quan vừa thắng — rollback để bài không rời hàng đợi");
    }

    public static JudgingException languageNotAvailable(String code) {
        return new JudgingException(
                Kind.INVALID,
                "submission.language_not_available",
                "Ngôn ngữ này hiện không nhận bài nộp.",
                "languages.code không tồn tại hoặc enabled=false: " + code);
    }

    public static JudgingException invalidCursor(String cursor) {
        return new JudgingException(
                Kind.INVALID,
                "submission.invalid_cursor",
                "Tham số phân trang không hợp lệ.",
                "cursor không phải số nguyên: " + cursor);
    }

    public static JudgingException dangBaoTri() {
        return new JudgingException(
                Kind.UNAVAILABLE,
                "submission.maintenance",
                "Hệ thống đang tạm ngừng nhận bài nộp để bảo trì. "
                        + "Bài đã nộp trước đó vẫn đang được chấm bình thường.",
                "system_settings['submissions.accepting'] = false");
    }

    public static JudgingException rejudgeTrongKyThi() {
        return new JudgingException(
                Kind.CONFLICT,
                "rejudge.contest_dang_chay",
                "Đang có kỳ thi diễn ra. Chấm lại hàng loạt chỉ chạy được khi không kỳ thi nào mở.",
                "FR-ADM-01: ContestWindowQuery.coKyThiDangChay() = true");
    }

    public static JudgingException rejudgeDaTat() {
        return new JudgingException(
                Kind.CONFLICT,
                "rejudge.da_tat",
                "Chấm lại hàng loạt đang bị tắt trên toàn hệ thống.",
                "system_settings['rejudge.enabled'] = false");
    }
}
