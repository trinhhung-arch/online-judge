package dev.oj.judging.domain;

import dev.oj.contract.Sha256;

import java.nio.charset.StandardCharsets;

/**
 * Nội dung một bài nộp, khử trùng lặp theo {@code sha256} — bảng {@code source_blobs} ở V3.
 *
 * <h2>Vì sao hash là khoá chính chứ không phải một id sinh tự động</h2>
 * Cùng một chuỗi 64 ký tự này đóng ba vai, và cả ba chỉ hoạt động nếu hai tiến trình băm y
 * hệt nhau — nên phép băm nằm ở {@link Sha256} trong {@code oj-contract}, không phải ở đây:
 * <ul>
 *   <li>khoá khử trùng lặp trong DB ({@code INSERT ... ON CONFLICT DO NOTHING});</li>
 *   <li>khoá cache biên dịch của worker — trong contest người ta nộp lại rất nhiều, và tỉ lệ
 *       trúng cao là một trong bốn tối ưu ROI cao nhất ({@code nfrplan.md} 2.3);</li>
 *   <li>khoá cache của AI review ở tuần 14–15: cùng source thì trả bản đã lưu,
 *       <b>không gọi LLM, không trừ quota</b> (FR-AI-06).</li>
 * </ul>
 *
 * <h2>Giới hạn 64KB được kiểm ở cả domain lẫn DB</h2>
 * Hai lần kiểm cho hai loại lỗi khác nhau: ở đây bắt được lỗi <i>của người dùng</i> và trả
 * lời họ bằng tiếng người; {@code CHECK (byte_size <= 65536)} ở V3 bắt được lỗi <i>của lập
 * trình viên</i> — một đường ghi nào đó quên gọi {@link #of(String)}. Hàng rào thứ hai không
 * bao giờ nên nổ, và chính vì thế mà nó phải có.
 *
 * @param sha256    64 ký tự hex chữ thường, đúng dạng cột {@code CHAR(64)}
 * @param content   mã nguồn thô của người dùng — <b>dữ liệu không đáng tin</b>. Không log,
 *                  không đưa vào thông báo lỗi, không nối vào phần chỉ thị của prompt LLM
 * @param byteSize  số byte UTF-8 của {@code content}, không phải số ký tự
 */
public record SourceBlob(String sha256, String content, int byteSize) {

    public SourceBlob {
        if (content == null) {
            throw new NullPointerException("content");
        }
        if (!Sha256.isHex(sha256)) {
            throw new IllegalArgumentException("sha256 phải là 64 ký tự hex chữ thường");
        }
        if (byteSize <= 0) {
            throw JudgingException.emptySource();
        }
        // Giới hạn nghiệp vụ -> ngoại lệ có câu chữ cho người dùng, kể cả khi ai đó gọi thẳng
        // constructor thay vì of(): một bài 70KB phải ra 400 với lý do rõ, không phải 500.
        if (byteSize > DomainRules.MAX_SOURCE_BYTES) {
            throw JudgingException.sourceTooLarge(byteSize);
        }
        // Lỗi lập trình viên, không phải lỗi người dùng: byteSize đi kèm content phải là số
        // byte thật của chính nó. Lệch nhau nghĩa là dòng trong DB nói dối về kích thước.
        if (byteSize != utf8Length(content)) {
            throw new IllegalArgumentException(
                    "byteSize (" + byteSize + ") không khớp độ dài UTF-8 thật ("
                            + utf8Length(content) + ")");
        }
    }

    /**
     * Đường vào duy nhất từ phía người dùng — {@code SubmitSolutionUseCase} gọi hàm này.
     *
     * <p><b>Kiểm kích thước trước khi băm, không phải sau.</b> Băm là O(n): một request mang
     * 50MB sẽ tốn 50MB công băm rồi mới bị từ chối, và 500 request như thế cùng lúc là ngân
     * sách 300ms bốc hơi (P2). Thứ tự hai dòng dưới đây có ý nghĩa hiệu năng, đừng đảo.
     *
     * @throws JudgingException nếu source rỗng hoặc vượt 64KB — cả hai đều là
     *                          {@code Kind.INVALID}, tức 400 kèm câu chữ cho người dùng
     */
    public static SourceBlob of(String content) {
        if (content == null || content.isBlank()) {
            throw JudgingException.emptySource();
        }
        int bytes = utf8Length(content);
        if (bytes > DomainRules.MAX_SOURCE_BYTES) {
            throw JudgingException.sourceTooLarge(bytes);
        }
        return new SourceBlob(Sha256.hexOf(content), content, bytes);
    }

    /**
     * Đếm theo <b>byte</b> vì {@code CHECK} trong DB là {@code octet_length}, và vì đó là con
     * số đúng về mặt nghiệp vụ: một bài toàn tên biến tiếng Việt hay comment Unicode chạm
     * trần byte trước khi chạm trần ký tự. Đếm bằng {@code content.length()} là để lọt một
     * bài gấp ba giới hạn.
     */
    private static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * <b>Không bao giờ chứa {@link #content}.</b>
     *
     * <p>Record sinh sẵn {@code toString()} in ra mọi thành phần. Với record này thì bản sinh
     * sẵn ấy là một đường rò rỉ: một dòng {@code log.info("... {}", blob)} viết lúc 2 giờ
     * sáng là toàn bộ mã nguồn của người dùng nằm trong file log — bất biến #9, và là loại rò
     * rỉ dễ quên nhất vì nó không đi qua API. Ghi đè ở đây làm lối tắt đó biến mất hẳn.
     */
    @Override
    public String toString() {
        return "SourceBlob[sha256=" + sha256 + ", byteSize=" + byteSize + "]";
    }
}
