package dev.oj.judging.infrastructure;

import dev.oj.judging.application.published.JudgingQueries;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Hiện thực {@link JudgingQueries}. Pool {@code app} — bảng xếp hạng là việc của phía đọc,
 * không phải đường verdict.
 *
 * <h2>Cả hai câu đều chỉ đọc, và không câu nào không có LIMIT</h2>
 * Bất biến #8. {@code submissions} sẽ có hàng triệu dòng, và một kỳ thi lớn có hàng chục
 * nghìn bài — đủ để một câu không giới hạn làm cạn bộ nhớ của tiến trình API.
 */
@Repository
public class JdbcJudgingQueries implements JudgingQueries {

    private static final String TRONG_CONTEST = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
              FROM submissions
             WHERE contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    /**
     * ★ Như {@link #TRONG_CONTEST}, nhưng <b>dừng lại ở bài chưa chấm xong đầu tiên</b>.
     *
     * <h2>Vì sao đường cập nhật cần câu riêng này</h2>
     * {@code StandingsUpdater} tiến một watermark duy nhất theo {@code id}. Nếu lô trả về bỏ
     * qua một bài chưa {@code DONE} rồi lấy bài id lớn hơn đã {@code DONE}, watermark vượt qua
     * bài kia và nó <b>vĩnh viễn</b> không vào bảng xếp hạng.
     *
     * <p>Điều đó không hiếm — nó là chuyện thường: thứ tự claim theo id, nhưng thứ tự
     * <i>chấm xong</i> theo độ nặng của bài. Một bài TLE chạy hết giới hạn thời gian trên mọi
     * test, một bài AC cùng đề xong trong vài mili giây, và 6 slot chạy song song. Đo được
     * trên Postgres thật: lô 1 áp 1 bài, lô 2 áp 0 bài, bảng còn đúng một người trong hai.
     *
     * <p>{@code id < ALL (…)} trên tập rỗng là {@code TRUE}, nên khi không có bài nào đang
     * chấm dở thì câu này trùng khít {@link #TRONG_CONTEST}.
     *
     * <h2>Đánh đổi đã chọn, và vì sao chọn chiều này</h2>
     * Một bài kẹt làm bảng của kỳ thi đó đứng lại cho tới khi nó xong (reaper bảo đảm điều đó
     * xảy ra, chậm nhất sau {@code oj.judge.lease}). Bảng đứng tạm là sai <b>an toàn</b>: nó
     * tự đúng lại. Bảng thiếu một bài thì không, và không ai kiểm lại một thứ hạng.
     *
     * <p>Bỏ bộ lọc {@code status} ở lớp trong <b>không</b> đổi đường truy cập: vẫn là quét
     * khoá chính từ {@code :sau} đi lên, chỉ ít vị từ hơn.
     */
    private static final String TRONG_CONTEST_LIEN_MACH = """
            WITH lo AS (
                SELECT id, user_id, problem_id, verdict, score, created_at, status
                  FROM submissions
                 WHERE contest_id = :contestId
                   AND id > :sau
                 ORDER BY id
                 LIMIT :gioiHan
            )
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
              FROM lo
             WHERE status = 'DONE'
               AND id < ALL (SELECT id FROM lo WHERE status <> 'DONE')
             ORDER BY id
            """;

    /**
     * Truy vấn 11 của {@code docs/sql/duong_nong.sql}, viết trọn vẹn.
     *
     * <p>Điều kiện {@code problem_id} đứng trước để {@code ix_submissions_problem_recent}
     * được dùng; {@code contest_id} là bộ lọc thêm trên các dòng đã cắt. Viết ngược lại thì
     * Postgres phải quét theo {@code contest_id} — cột <b>cố ý không có index</b>.
     */
    private static final String CUA_DE = """
            SELECT id, user_id, problem_id, verdict, COALESCE(score, 0) AS score, created_at
              FROM submissions
             WHERE problem_id = :problemId
               AND contest_id = :contestId
               AND status = 'DONE'
               AND id > :sau
             ORDER BY id
             LIMIT :gioiHan
            """;

    private static final RowMapper<ScoredSubmission> MAPPER = (rs, i) -> new ScoredSubmission(
            rs.getLong("id"),
            rs.getLong("user_id"),
            rs.getLong("problem_id"),
            rs.getString("verdict"),
            rs.getInt("score"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcClient jdbc;

    public JdbcJudgingQueries(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ScoredSubmission> baiDaChamTrongContest(long contestId, long sauSubmissionId,
                                                       int gioiHan) {
        return jdbc.sql(TRONG_CONTEST)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<ScoredSubmission> baiDaChamLienMach(long contestId, long sauSubmissionId,
                                                    int gioiHan) {
        return jdbc.sql(TRONG_CONTEST_LIEN_MACH)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }

    @Override
    public List<ScoredSubmission> baiDaChamCuaDe(long contestId, long problemId,
                                                 long sauSubmissionId, int gioiHan) {
        return jdbc.sql(CUA_DE)
                .param("problemId", problemId)
                .param("contestId", contestId)
                .param("sau", sauSubmissionId)
                .param("gioiHan", gioiHan)
                .query(MAPPER)
                .list();
    }
}
