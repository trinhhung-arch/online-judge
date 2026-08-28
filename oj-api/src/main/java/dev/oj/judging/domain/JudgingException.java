package dev.oj.judging.domain;

import dev.oj.platform.error.DomainException;

/**
 * Lỗi nghiệp vụ của module {@code judging}. {@code CLAUDE.md} mục 7: mỗi module có ngoại lệ
 * riêng, không ai ném {@code RuntimeException} trần.
 *
 * <h2>Ba loại lỗi, và chỉ một loại đi qua class này</h2>
 * <ul>
 *   <li><b>Người dùng làm sai</b> — source 70KB, ngôn ngữ đã tắt, nộp lại quá nhanh.
 *       → class này, có {@code publicMessage} viết cho người đọc.</li>
 *   <li><b>Lập trình viên làm sai</b> — {@code markDone} khi bài chưa được claim,
 *       {@code attempt} lùi lại. → {@code IllegalStateException} / {@code IllegalArgumentException}:
 *       không có câu chữ nào cho người dùng vì <i>không người dùng nào gây ra được nó</i>,
 *       và nó phải ồn ào để lộ ra trong test.</li>
 *   <li><b>Worker gửi dữ liệu lệch</b> — log compiler 40KB, thời gian âm.
 *       → <b>cắt bớt hoặc bỏ qua, tuyệt đối không ném</b>. Ném là: API từ chối kết quả →
 *       reaper thu hồi → worker chấm lại → gửi đúng dữ liệu đó → từ chối tiếp. Một vòng lặp
 *       vô hạn ăn hết năng lực chấm vì một bài có log dài. Xem {@link JudgeRun}.</li>
 * </ul>
 *
 * <h2>{@code notFound} gộp hai trường hợp làm một, và đó là cố ý</h2>
 * Bài nộp của người khác phải trả <b>404, không phải 403</b> — 403 xác nhận "có tồn tại một
 * bài nộp id này", đủ để dò ra ai nộp bài nào, và trong contest thì đó là thông tin không
 * được lộ. Nhưng lớp bảo vệ thật nằm ở <b>câu query</b>: điều kiện chủ sở hữu đi kèm trong
 * {@code WHERE}, nên repository đơn giản là không trả về dòng nào và use-case ném
 * {@link #submissionNotFound(long)} một cách tự nhiên. Lọc bằng một câu {@code if} sau khi đã
 * load là lỗ hổng IDOR ngay cả khi câu {@code if} viết đúng ({@code oj-api/CLAUDE.md} mục 2).
 */
public class JudgingException extends DomainException {

    protected JudgingException(Kind kind, String code, String publicMessage, String logMessage) {
        super(kind, code, publicMessage, logMessage);
    }

    /**
     * FR-SUB-01 — source vượt 64KB.
     *
     * <p>Câu ra người dùng nói rõ <b>giới hạn</b> và <b>kích thước thật của họ</b>: một thông
     * báo "file quá lớn" không kèm hai con số đó buộc người ta phải đoán, và họ sẽ đoán bằng
     * cách nộp lại vài lần — mỗi lần một lượt rate limit.
     *
     * @param actualBytes số byte UTF-8 thật, dùng cho cả hai câu. Không log nội dung source
     *                    (bất biến #9) — chỉ độ dài của nó
     */
    public static JudgingException sourceTooLarge(int actualBytes) {
        return new JudgingException(
                Kind.INVALID,
                "submission.source_too_large",
                "Mã nguồn dài " + actualBytes + " byte, vượt giới hạn "
                        + DomainRules.MAX_SOURCE_BYTES + " byte (64 KB).",
                "source " + actualBytes + " byte > " + DomainRules.MAX_SOURCE_BYTES);
    }

    /** Nộp một ô trống. Chặn ở đây để không tốn một hàng {@code source_blobs} và một lượt chấm. */
    public static JudgingException emptySource() {
        return new JudgingException(
                Kind.INVALID,
                "submission.empty_source",
                "Mã nguồn không được để trống.",
                "source rỗng hoặc chỉ có khoảng trắng");
    }

    /**
     * Không tìm thấy bài nộp — <b>hoặc người gọi không được phép thấy nó</b>.
     *
     * <p>Một câu chữ cho cả hai trường hợp. Xem javadoc của lớp: phân biệt chúng là cho không
     * người lạ một công cụ dò xem những id nào có thật.
     */
    public static JudgingException submissionNotFound(long id) {
        return new JudgingException(
                Kind.NOT_FOUND,
                "submission.not_found",
                "Không tìm thấy bài nộp này.",
                "submission id=" + id + " không tồn tại, hoặc người gọi không được phép thấy");
    }

    /**
     * FR-SUB-01 — ngôn ngữ không tồn tại hoặc đã bị tắt.
     *
     * <p>Cũng gộp hai trường hợp: với người nộp bài thì "không có ngôn ngữ này" và "ngôn ngữ
     * này vừa bị tắt vì máy chấm chưa cài xong toolchain" dẫn tới cùng một hành động — chọn
     * ngôn ngữ khác. Log thì ghi rõ mã họ gửi để bạn biết UI đang hiện một lựa chọn đã chết.
     */
    public static JudgingException languageNotAvailable(String code) {
        return new JudgingException(
                Kind.INVALID,
                "submission.language_not_available",
                "Ngôn ngữ này hiện không nhận bài nộp.",
                "languages.code không tồn tại hoặc enabled=false: " + code);
    }

    /**
     * Con trỏ phân trang không đọc được (FR-SUB-07).
     *
     * <p>Từ chối thay vì im lặng quay về trang đầu: một client gửi cursor hỏng mà vẫn nhận
     * được 200 sẽ lặp vô hạn trang đầu, và không ai phát hiện ra trừ khi có người ngồi đếm.
     */
    public static JudgingException invalidCursor(String cursor) {
        return new JudgingException(
                Kind.INVALID,
                "submission.invalid_cursor",
                "Tham số phân trang không hợp lệ.",
                "cursor không phải số nguyên: " + cursor);
    }
}
