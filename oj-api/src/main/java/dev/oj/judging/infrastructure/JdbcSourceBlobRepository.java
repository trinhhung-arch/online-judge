package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.SourceBlobRepository;
import dev.oj.judging.domain.SourceBlob;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Truy vấn 1a của {@code docs/sql/duong_nong.sql}. Pool {@code app} — nó chạy bên trong
 * transaction nộp bài, ngân sách 50ms cho cả ba câu.
 */
@Repository
public class JdbcSourceBlobRepository implements SourceBlobRepository {

    /**
     * {@code ON CONFLICT DO NOTHING} chứ không phải kiểm-rồi-ghi.
     *
     * <p>Hai người nộp cùng một đoạn code trong cùng một mili giây là chuyện rất thường trong
     * contest — ai đó chia sẻ template và mười người dán vào. Mẫu {@code SELECT} rồi
     * {@code INSERT} sẽ ném lỗi khoá trùng cho một trong hai người, và người đó mất bài nộp
     * vì một lý do không liên quan gì tới họ.
     */
    private static final String SAVE_IF_ABSENT = """
            INSERT INTO source_blobs (sha256, content, byte_size)
            VALUES (:sha256, :content, :byteSize)
            ON CONFLICT (sha256) DO NOTHING
            """;

    private final JdbcClient jdbc;

    public JdbcSourceBlobRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void saveIfAbsent(SourceBlob blob) {
        jdbc.sql(SAVE_IF_ABSENT)
                .param("sha256", blob.sha256())
                .param("content", blob.content())
                .param("byteSize", blob.byteSize())
                .update();
        // Không đọc số dòng trả về: 0 nghĩa là "đã có sẵn", 1 nghĩa là "vừa chèn", và người
        // gọi không cần phân biệt — khoá đã nằm trong tay họ từ trước.
    }
}
