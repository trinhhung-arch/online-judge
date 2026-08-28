package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeQueueRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Hàng đợi bền. Truy vấn 1c · 2 · 3a · 3' · 4 · 12 của {@code docs/sql/duong_nong.sql}.
 *
 * <h2>Hai pool, như {@code JdbcSubmissionRepository} — vì cùng một lý do</h2>
 * <pre>
 *   POOL   PHƯƠNG THỨC                                      TRANSACTION CỦA
 *   app    enqueue                                          @Transactional (nộp bài)
 *   judge  claim · releaseWithOptimisticLock · reapExpired   @JudgeTransactional
 *          · retryIe · queueDepth
 * </pre>
 *
 * <p>{@code enqueue} <b>phải</b> chạy trong chính transaction nộp bài. Đẩy nó sang pool
 * {@code judge} là tạo một hàng đợi trỏ tới một {@code submission} có thể không bao giờ được
 * commit — worker sẽ claim một bài không tồn tại.
 *
 * <p>⚠️ Ở M6, {@code RejudgeJob} cũng gọi {@code enqueue}, nhưng dưới
 * {@code @JudgeTransactional}. Lúc đó câu này sẽ chạy ngoài transaction ấy. Với rejudge thì
 * hệ quả nhẹ (một hàng thừa trong hàng đợi, verdict cũ vẫn nguyên) nhưng <b>vẫn phải xử lý</b>:
 * hoặc tách thành một phương thức riêng dùng {@code judgeJdbc}, hoặc cho {@code RejudgeJob}
 * chạy trên pool {@code app}. Ghi ở đây để Bước 6.3 không phải phát hiện lại.
 */
@Repository
public class JdbcJudgeQueueRepository implements JudgeQueueRepository {

    // ---- truy vấn 1c ----
    private static final String ENQUEUE = """
            INSERT INTO judge_queue (submission_id, priority, attempt)
            VALUES (:submissionId, :priority, 0)
            ON CONFLICT (submission_id) DO NOTHING
            """;

    /**
     * Truy vấn 2. <b>{@code FOR UPDATE SKIP LOCKED} là thứ làm cho N worker không bao giờ
     * nhận cùng một bài</b>, mà không cần khoá phân tán nào. Bỏ nó đi thì các worker xếp hàng
     * chờ nhau và throughput sập về một luồng; tách thành {@code SELECT} rồi {@code UPDATE}
     * thì mở ra một cửa sổ để hai worker cùng thấy một dòng.
     *
     * <p>{@code attempt} tăng ở <b>đây và chỉ ở đây</b>. Chính điều đó vô hiệu hoá kết quả
     * trả về muộn của một worker đã bị reaper thu hồi — không cần thêm cơ chế nào.
     *
     * <p>{@code claimed_by_host} phân giải bằng sub-select: worker gửi tên máy và không biết
     * {@code judge_hosts.id} tồn tại (bất biến #3). Máy chưa đăng ký thì cột nhận NULL và
     * worker vẫn chấm được ngay — đó là S2 ("worker mới join: 0 thao tác phía API").
     *
     * <p><b>Một chỗ lệch có chủ ý so với {@code duong_nong.sql}:</b> tài liệu viết
     * {@code (:leaseSeconds || ' seconds')::interval}. Ở đây dùng
     * {@code make_interval(secs => :leaseSeconds)} — cùng kết quả, nhưng nhận thẳng số nguyên
     * thay vì đi vòng qua chuỗi. Bản gốc dựa vào việc Postgres tự phân giải
     * {@code int4 || unknown} thành {@code anynonarray || text}, và với một tham số bind thì
     * phép phân giải đó phụ thuộc kiểu mà driver gửi lên. {@code make_interval} không có chỗ
     * nào để đoán.
     */
    private static final String CLAIM = """
            WITH picked AS (
                SELECT submission_id
                  FROM judge_queue
                 WHERE claimed_at IS NULL
                 ORDER BY priority, enqueued_at, submission_id
                 LIMIT 1
                   FOR UPDATE SKIP LOCKED
            )
            UPDATE judge_queue q
               SET claimed_at      = now(),
                   lease_until     = now() + make_interval(secs => :leaseSeconds),
                   claimed_by_host = (SELECT id FROM judge_hosts WHERE name = :hostName),
                   attempt         = q.attempt + 1
              FROM picked p
             WHERE q.submission_id = p.submission_id
            RETURNING q.submission_id, q.attempt
            """;

    /**
     * Không có trong 12 truy vấn của {@code duong_nong.sql} — file đó dừng ở chỗ claim trả về
     * {@code (submission_id, attempt)}, nhưng để dựng được một {@code JudgeJobDto} thì còn
     * thiếu mã nguồn và thông số ngôn ngữ. Đây là câu bù vào chỗ trống đó.
     *
     * <p>Gộp cả hai thành một {@code JOIN} thay vì hai lượt đọc: pool {@code judge} chỉ có 6
     * connection, và mỗi lượt round-trip thừa là một slot bị giữ lâu hơn cần thiết.
     */
    private static final String CLAIM_PAYLOAD = """
            SELECT s.id AS submission_id, s.problem_id, s.testdata_version,
                   s.source_sha256, b.content AS source_content,
                   l.code AS language_code, l.compile_command, l.run_command,
                   l.compile_time_limit_ms, l.compile_memory_kb,
                   l.time_multiplier, l.startup_overhead_ms, l.memory_overhead_kb
              FROM submissions  s
              JOIN source_blobs b ON b.sha256 = s.source_sha256
              JOIN languages    l ON l.id     = s.language_id
             WHERE s.id = :submissionId
            """;

    /**
     * ★ Truy vấn 3a — <b>khoá lạc quan</b>, câu lệnh đầu tiên của transaction ghi verdict.
     *
     * <p>Mở rộng so với bản trong {@code duong_nong.sql}: thêm {@code USING submissions} để
     * lấy luôn {@code language_id} và {@code testdata_version}. Hai cột đó là bắt buộc cho
     * {@code INSERT judge_runs} ngay sau đây, mà bản gốc không có nguồn nào cung cấp — giữ
     * nguyên chữ thì phải thêm một câu {@code SELECT} vào đúng transaction ngắn nhất và nóng
     * nhất của hệ thống.
     *
     * <p>0 dòng nghĩa là kết quả trùng, hoặc kết quả của một attempt đã bị reaper thu hồi.
     * Bỏ qua im lặng — đó là cơ chế, không phải lỗi.
     */
    private static final String RELEASE_WITH_OPTIMISTIC_LOCK = """
            DELETE FROM judge_queue q
             USING submissions s
             WHERE q.submission_id = :submissionId
               AND q.attempt       = :attempt
               AND s.id            = q.submission_id
            RETURNING q.submission_id, q.attempt, s.language_id, s.testdata_version
            """;

    /**
     * Truy vấn 3' — FR-SUB-12. Điều kiện {@code attempt} khiến câu này mang luôn tính chất của
     * khoá lạc quan, nên nhánh IE rẽ trước {@link #releaseWithOptimisticLock} mà không mất an toàn.
     *
     * <p>{@code enqueued_at = now()} đẩy bài xuống cuối hàng đợi: một bài vừa gây IE không nên
     * chen lên trước những bài đang chờ, nhất là khi nguyên nhân IE có thể lặp lại.
     */
    private static final String RETRY_IE = """
            UPDATE judge_queue
               SET claimed_at      = NULL,
                   lease_until     = NULL,
                   claimed_by_host = NULL,
                   ie_retry_count  = ie_retry_count + 1,
                   enqueued_at     = now()
             WHERE submission_id  = :submissionId
               AND attempt        = :attempt
               AND ie_retry_count < :maxIeRetries
            RETURNING submission_id
            """;

    // ---- truy vấn 4. KHÔNG tăng attempt: lần claim kế tiếp mới tăng ----
    private static final String REAP_EXPIRED = """
            UPDATE judge_queue
               SET claimed_at      = NULL,
                   lease_until     = NULL,
                   claimed_by_host = NULL
             WHERE claimed_at IS NOT NULL
               AND lease_until  < now()
            RETURNING submission_id
            """;

    /**
     * Truy vấn 12 — đếm trên bảng vài trăm dòng, <b>không bao giờ {@code COUNT(*)} trên
     * {@code submissions}</b> (bảng đó có hàng triệu dòng).
     *
     * <p>Trả về {@code min(enqueued_at)} thay vì {@code oldest_wait_ms} như bản trong tài
     * liệu: {@code QueueStats} mang một {@link java.time.Instant}, và để tầng trên tự tính
     * khoảng cách bằng {@code Clock} của ứng dụng thì nó test được mà không cần DB.
     */
    private static final String QUEUE_DEPTH = """
            SELECT count(*) FILTER (WHERE claimed_at IS NULL)     AS waiting,
                   count(*) FILTER (WHERE claimed_at IS NOT NULL) AS claimed,
                   min(enqueued_at) FILTER (WHERE claimed_at IS NULL) AS oldest_enqueued_at
              FROM judge_queue
            """;

    private final JdbcClient appJdbc;
    private final JdbcClient judgeJdbc;

    public JdbcJudgeQueueRepository(@Qualifier("appJdbcClient") JdbcClient appJdbc,
                                    @Qualifier("judgeJdbcClient") JdbcClient judgeJdbc) {
        this.appJdbc = appJdbc;
        this.judgeJdbc = judgeJdbc;
    }

    @Override
    public void enqueue(long submissionId, int priority) {
        // POOL app — cùng transaction với INSERT submissions. Xem cảnh báo ở javadoc lớp.
        appJdbc.sql(ENQUEUE)
                .param("submissionId", submissionId)
                .param("priority", priority)
                .update();
    }

    @Override
    public Optional<ClaimedJob> claim(String hostName, int leaseSeconds) {
        // POOL judge — @JudgeTransactional. Hai câu, một transaction.
        Optional<long[]> picked = judgeJdbc.sql(CLAIM)
                .param("hostName", hostName)
                .param("leaseSeconds", leaseSeconds)
                .query((rs, n) -> new long[]{rs.getLong("submission_id"), rs.getInt("attempt")})
                .optional();
        if (picked.isEmpty()) {
            return Optional.empty();     // hàng đợi rỗng -> 204
        }
        long submissionId = picked.get()[0];
        int attempt = (int) picked.get()[1];
        return judgeJdbc.sql(CLAIM_PAYLOAD)
                .param("submissionId", submissionId)
                .query(payloadMapper(attempt))
                .optional();
    }

    @Override
    public Optional<ReleasedSubmission> releaseWithOptimisticLock(long submissionId, int attempt) {
        // POOL judge — câu lệnh ĐẦU TIÊN của transaction ghi verdict.
        return judgeJdbc.sql(RELEASE_WITH_OPTIMISTIC_LOCK)
                .param("submissionId", submissionId)
                .param("attempt", attempt)
                .query((rs, n) -> new ReleasedSubmission(
                        rs.getLong("submission_id"),
                        rs.getInt("attempt"),
                        rs.getInt("language_id"),
                        rs.getInt("testdata_version")))
                .optional();
    }

    @Override
    public List<Long> reapExpired() {
        // POOL judge — @JudgeTransactional, cùng transaction với markQueued().
        return judgeJdbc.sql(REAP_EXPIRED)
                .query(Long.class)
                .list();
    }

    @Override
    public boolean retryIe(long submissionId, int attempt, int maxRetries) {
        // POOL judge — rẽ TRƯỚC khoá lạc quan.
        return judgeJdbc.sql(RETRY_IE)
                .param("submissionId", submissionId)
                .param("attempt", attempt)
                .param("maxIeRetries", maxRetries)
                .query(Long.class)
                .optional()
                .isPresent();
    }

    @Override
    public QueueStats queueDepth() {
        // POOL judge — trang trạng thái công khai đọc cùng nguồn với đường verdict.
        return judgeJdbc.sql(QUEUE_DEPTH)
                .query((rs, n) -> {
                    var oldest = rs.getTimestamp("oldest_enqueued_at");
                    return new QueueStats(rs.getInt("waiting"), rs.getInt("claimed"),
                            oldest == null ? null : oldest.toInstant());
                })
                .single();
    }

    private static RowMapper<ClaimedJob> payloadMapper(int attempt) {
        return (rs, n) -> new ClaimedJob(
                rs.getLong("submission_id"),
                attempt,
                rs.getLong("problem_id"),
                rs.getInt("testdata_version"),
                rs.getString("source_sha256"),
                rs.getString("source_content"),
                new ClaimedJob.LanguageSpec(
                        rs.getString("language_code"),
                        rs.getString("compile_command"),
                        rs.getString("run_command"),
                        rs.getInt("compile_time_limit_ms"),
                        rs.getInt("compile_memory_kb"),
                        rs.getBigDecimal("time_multiplier"),
                        rs.getInt("startup_overhead_ms"),
                        rs.getInt("memory_overhead_kb")));
    }
}
