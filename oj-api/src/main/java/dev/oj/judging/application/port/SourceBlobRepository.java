package dev.oj.judging.application.port;

import dev.oj.judging.domain.SourceBlob;

/**
 * Port lưu nội dung bài nộp, khử trùng lặp theo {@code sha256}.
 *
 * <p>Chỉ có một phương thức, và nó cố ý không có kiểu trả về.
 */
public interface SourceBlobRepository {

    /**
     * {@code INSERT ... ON CONFLICT (sha256) DO NOTHING}.
     *
     * <p><b>Vì sao không phải {@code SELECT} rồi {@code INSERT} nếu chưa có:</b> hai người nộp
     * cùng một đoạn code trong cùng một mili giây — chuyện rất thường xảy ra trong contest khi
     * ai đó chia sẻ template — sẽ làm mẫu kiểm-rồi-ghi ném lỗi khoá trùng cho một trong hai
     * người. {@code ON CONFLICT DO NOTHING} biến chuyện đó thành không có gì xảy ra.
     *
     * <p><b>Vì sao không trả về gì:</b> "đã có sẵn" và "vừa được chèn" là hai chuyện mà người
     * gọi không cần phân biệt — khoá đã nằm trong tay họ từ trước ({@code blob.sha256()}). Trả
     * về {@code boolean} chỉ tạo ra một nhánh {@code if} không ai biết viết gì vào.
     */
    void saveIfAbsent(SourceBlob blob);
}
