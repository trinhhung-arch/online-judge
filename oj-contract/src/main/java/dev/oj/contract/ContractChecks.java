package dev.oj.contract;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Kiểm tra dùng chung cho các record trong package này. Package-private: đây là chi tiết
 * cài đặt của contract, không phải một phần của contract.
 *
 * <p><b>Nguyên tắc chọn giữa "ném lỗi" và "cắt bớt":</b>
 * <ul>
 *   <li><b>Ném lỗi</b> với dữ liệu đi <i>vào</i> worker (job). Nó đã được validate ở
 *       {@code SubmitSolutionUseCase} rồi, nên sai ở đây nghĩa là có bug — và bug thì
 *       nên ồn ào.</li>
 *   <li><b>Cắt bớt</b> với dữ liệu đi <i>ra</i> từ worker (log compiler, meta của isolate).
 *       Nếu ném lỗi ở đây, API từ chối kết quả → reaper thu hồi → worker chấm lại → lại
 *       log dài y hệt → <b>vòng lặp vô hạn nuốt trọn năng lực chấm</b>. Một bài nộp có log
 *       compiler 40KB không đáng để đánh sập hàng đợi.</li>
 * </ul>
 */
final class ContractChecks {

    private static final String TRUNCATION_MARK = "\n… [đã cắt bớt]";

    private ContractChecks() {
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " không được rỗng");
        }
        return value;
    }

    static String requireSha256(String value, String field) {
        if (!Sha256.isHex(value)) {
            throw new IllegalArgumentException(
                    field + " phải là 64 ký tự hex chữ thường, nhận được: " + value);
        }
        return value;
    }

    static long requirePositive(long value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " phải dương, nhận được: " + value);
        }
        return value;
    }

    static int requireAtLeast(int value, int min, String field) {
        if (value < min) {
            throw new IllegalArgumentException(field + " phải >= " + min + ", nhận được: " + value);
        }
        return value;
    }

    static int requireRange(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    field + " phải nằm trong [" + min + ".." + max + "], nhận được: " + value);
        }
        return value;
    }

    static Integer requireNullOrRange(Integer value, int min, int max, String field) {
        return value == null ? null : requireRange(value, min, max, field);
    }

    /** Bản sao bất biến; {@code null} thành danh sách rỗng. Ném NPE nếu có phần tử null. */
    static <T> List<T> frozen(List<T> value) {
        return value == null ? List.of() : List.copyOf(value);
    }

    static int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Cắt chuỗi về tối đa {@code maxBytes} byte UTF-8, cắt đúng ranh giới ký tự.
     *
     * <p>Đếm theo <b>byte</b> chứ không theo ký tự vì ràng buộc trong DB là
     * {@code octet_length(compile_log) <= 32768}. Một log đầy tiếng Việt hoặc ký tự Unicode
     * trong tên biến sẽ vượt byte trước khi vượt ký tự.
     */
    static String truncateUtf8(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int budget = maxBytes - TRUNCATION_MARK.getBytes(StandardCharsets.UTF_8).length;
        if (budget <= 0) {
            return "";
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.IGNORE)
                .onUnmappableCharacter(CodingErrorAction.IGNORE);
        CharBuffer out = CharBuffer.allocate(budget);
        decoder.decode(ByteBuffer.wrap(bytes, 0, budget), out, true);
        decoder.flush(out);
        out.flip();
        return out + TRUNCATION_MARK;
    }
}
