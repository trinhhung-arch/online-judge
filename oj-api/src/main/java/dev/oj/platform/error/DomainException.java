package dev.oj.platform.error;

import java.time.Duration;

/**
 * Gốc của mọi lỗi nghiệp vụ. {@code CLAUDE.md} mục 7: <i>"Không ném {@code RuntimeException} trần."</i>
 *
 * <h2>Hai thông điệp, không phải một</h2>
 * Đây là điểm quan trọng nhất của class này. Mỗi ngoại lệ mang <b>hai</b> câu:
 *
 * <ul>
 *   <li>{@link #publicMessage()} — thứ duy nhất được phép ra tới client. Do người viết
 *       use-case soạn, và người đó chịu trách nhiệm rằng nó an toàn.</li>
 *   <li>{@link #getMessage()} — chỉ vào log. Được phép chi tiết hơn.</li>
 * </ul>
 *
 * <p>Vì sao tách: {@code CLAUDE.md} mục 4.2 cấm error message làm lộ testdata, đường dẫn hệ
 * thống hay stack trace ra client. Nếu chỉ có một câu, thì một ngày nào đó sẽ có người viết
 * {@code throw new ...("không đọc được /var/oj/testdata/ab3f.../input07.txt")} cho tiện debug,
 * và câu đó đi thẳng ra HTTP. Với hai câu thì lối tắt ấy không tồn tại —
 * {@code GlobalExceptionHandler} chỉ đọc {@code publicMessage()}.
 *
 * <h2>Không có HTTP status trong này</h2>
 * Nó mang {@link Kind} — một khái niệm nghiệp vụ — và {@code GlobalExceptionHandler} mới ánh xạ
 * sang mã HTTP. Nhờ đó {@code domain} ném được ngoại lệ mà không cần biết HTTP tồn tại
 * (luật ArchUnit 1 và 2b).
 *
 * <h2>Mỗi module một class con</h2>
 * {@code SubmissionNotFoundException extends DomainException}, đặt trong
 * {@code dev.oj.judging.domain}. Không ném thẳng class này.
 */
public abstract class DomainException extends RuntimeException {

    /**
     * Loại lỗi, nói bằng ngôn ngữ nghiệp vụ. Ánh xạ sang HTTP là việc của tầng {@code api}.
     */
    public enum Kind {
        /** Không tìm thấy, <b>hoặc người gọi không có quyền thấy</b> — xem ghi chú NOT_FOUND. */
        NOT_FOUND,
        /** Đầu vào không hợp lệ: source quá dài, ngôn ngữ không tồn tại, tham số sai. */
        INVALID,
        /** Có quyền đăng nhập nhưng không đủ vai trò. */
        FORBIDDEN,
        /** Chưa đăng nhập, hoặc token hết hạn. */
        UNAUTHENTICATED,
        /** Trạng thái không cho phép: sửa đề đang trong contest, rejudge khi contest đang chạy. */
        CONFLICT,
        /** Chạm rate limit hoặc quota. Kèm {@link #retryAfter()}. */
        RATE_LIMITED,
        /** Phụ thuộc bên ngoài chết, hoặc hệ thống đang ở chế độ bảo trì. */
        UNAVAILABLE
    }

    private final Kind kind;
    private final String code;
    private final String publicMessage;
    private final Duration retryAfter;

    protected DomainException(Kind kind, String code, String publicMessage) {
        this(kind, code, publicMessage, publicMessage, null, null);
    }

    protected DomainException(Kind kind, String code, String publicMessage, String logMessage) {
        this(kind, code, publicMessage, logMessage, null, null);
    }

    protected DomainException(Kind kind, String code, String publicMessage, String logMessage,
                              Duration retryAfter, Throwable cause) {
        super(logMessage, cause);
        if (kind == null || code == null || code.isBlank()
                || publicMessage == null || publicMessage.isBlank()) {
            throw new IllegalArgumentException("DomainException cần đủ kind, code và publicMessage");
        }
        this.kind = kind;
        this.code = code;
        this.publicMessage = publicMessage;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * Mã ổn định để client xử lý theo chương trình, ví dụ {@code submission.rate_limited}.
     *
     * <p>Dùng mã chứ không dùng câu chữ: câu chữ sẽ đổi khi ai đó sửa lại cho dễ hiểu hơn,
     * và mọi đoạn {@code if (message.contains(...))} ở frontend sẽ hỏng im lặng.
     * Đặt tên {@code <mien>.<viec>}, chữ thường, gạch dưới.
     */
    public String code() {
        return code;
    }

    /** <b>Câu duy nhất được phép ra tới client.</b> */
    public String publicMessage() {
        return publicMessage;
    }

    /** Chỉ có nghĩa với {@link Kind#RATE_LIMITED}: bao lâu nữa thì thử lại được. */
    public Duration retryAfter() {
        return retryAfter;
    }

    // -------------------------------------------------------------------------
    // Ghi chú về NOT_FOUND — đọc trước khi viết use-case đọc dữ liệu.
    //
    // Một submission của người khác phải trả 404, KHÔNG phải 403. 403 xác nhận
    // "tồn tại một bài nộp id này" — đủ để dò ra ai đã nộp bài nào, và trong contest
    // thì đó là thông tin không được lộ.
    //
    // Cách làm đúng nằm ở tầng repository chứ không phải ở đây: điều kiện chủ sở hữu
    // phải nằm TRONG câu query (truy vấn 9 của duong_nong.sql), nên câu query đơn giản
    // là không trả về dòng nào và use-case ném NOT_FOUND một cách tự nhiên.
    // Lọc bằng một câu `if` sau khi đã load là lỗ hổng IDOR ngay cả khi câu `if` viết đúng
    // (oj-api/CLAUDE.md mục 2).
    // -------------------------------------------------------------------------
}
