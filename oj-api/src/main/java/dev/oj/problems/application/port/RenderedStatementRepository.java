package dev.oj.problems.application.port;

import java.util.Optional;

/**
 * Cache bản render đề bài — bảng {@code rendered_statements} (V2), FR-PROB-02 và P1.
 *
 * <h2>Vì sao cache nằm ở Postgres chứ không chỉ Redis</h2>
 * Comment trong V2 nói thẳng: <i>"Redis chết thì trang đề vẫn phải mở được"</i>
 * ({@code nfrplan.md} 7.2, degraded mode). Trang đề là thứ đầu tiên mọi người mở; nếu nó phụ
 * thuộc Redis thì một sự cố cache biến thành một sự cố toàn site.
 *
 * <p>Khoá là {@code (statement_hash, renderer_version)} — <b>không phải {@code problem_id}</b>.
 * Nghĩa là hai đề có nội dung giống hệt dùng chung một bản render, và quan trọng hơn: sửa đề
 * rồi sửa ngược lại thì bản cũ vẫn còn đó, không phải render lại.
 */
public interface RenderedStatementRepository {

    Optional<String> tim(String statementHash, String rendererVersion);

    /**
     * Ghi bản render. Idempotent — hai request cùng lúc cho một đề chưa cache sẽ cùng render
     * và cùng ghi, và cả hai đều đúng vì kết quả giống nhau theo định nghĩa của khoá.
     */
    void luu(String statementHash, String rendererVersion, String html);
}
