package dev.oj.contests.infrastructure;

import dev.oj.platform.contest.ContestWindowQuery;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;

/**
 * Hiện thực {@link ContestWindowQuery} — Bước 5.3. Pool {@code app}.
 *
 * <h2>★ Câu này chạy trên ĐƯỜNG NÓNG, nên nó phải rẻ</h2>
 * {@code SubmitSolutionUseCase} gọi nó ở mỗi lần nộp bài, trong ngân sách 300ms của P2. Cả hai
 * câu dưới đây đều là index scan trên {@code ix_contest_problems_problem}
 * ({@code (problem_id, contest_id)}) rồi một lượt tra khoá chính {@code contests} — và với
 * phần lớn đề (không thuộc kỳ thi nào) thì index scan trả về 0 dòng và dừng ngay.
 *
 * <p>Đó chính là lý do V7 tạo index ấy, và lý do {@code submissions.contest_id} <b>cố ý không
 * có index</b>: ngân sách index của bảng nóng được để dành cho chỗ khác.
 *
 * <h2>Thời gian lấy từ {@link Clock} của ứng dụng, không phải {@code now()} của Postgres</h2>
 * Hai lý do. Thứ nhất, test dựng được một kỳ thi "đang chạy" bằng {@code Clock.fixed} mà không
 * phải chờ đồng hồ thật. Thứ hai và quan trọng hơn: giờ bắt đầu và kết thúc của một kỳ thi là
 * thứ mà <b>mọi</b> phép kiểm phải nhất trí — {@code StandingsUpdater}, chốt truy cập, và job
 * đóng băng. Một chỗ dùng đồng hồ ứng dụng còn chỗ khác dùng đồng hồ database là hai chỗ có
 * thể lệch nhau vài trăm mili giây, và vài trăm mili giây ở giây cuối cùng của một kỳ thi là
 * một cuộc tranh cãi.
 */
@Repository
public class JdbcContestWindowQuery implements ContestWindowQuery {

    private static final String CONTEST_DANG_CHAY = """
            SELECT c.id
              FROM contest_problems cp
              JOIN contests c ON c.id = cp.contest_id
             WHERE cp.problem_id = :problemId
               AND c.starts_at <= :bayGio
               AND c.ends_at > :bayGio
             ORDER BY c.id
             LIMIT 1
            """;

    /**
     * Không {@code COUNT(*)}: {@code EXISTS} dừng ở dòng đầu tiên tìm thấy. Bảng
     * {@code contests} có hàng chục dòng, nhưng thói quen viết {@code COUNT} vào một câu chỉ
     * cần biết "có hay không" là thói quen sẽ theo người viết sang một bảng có hàng triệu.
     */
    private static final String CO_KY_THI_DANG_CHAY = """
            SELECT EXISTS (SELECT 1 FROM contests
                            WHERE starts_at <= :bayGio AND ends_at > :bayGio)
            """;

    /**
     * Đề bị khoá khi <b>tồn tại</b> một kỳ thi chứa nó mà người gọi không được vào.
     *
     * <p>Ba nhánh {@code NOT} đọc là: đề mở nếu kỳ thi đã kết thúc, <i>hoặc</i> người gọi đã
     * đăng ký và kỳ thi đang chạy. Mọi trường hợp còn lại — chưa mở, đang chạy mà chưa đăng
     * ký — là khoá.
     *
     * <p>{@code registration_required = FALSE} bỏ qua phép kiểm đăng ký, cho kỳ thi mở tự do.
     */
    private static final String BI_KHOA = """
            SELECT EXISTS (
                SELECT 1
                  FROM contest_problems cp
                  JOIN contests c ON c.id = cp.contest_id
                 WHERE cp.problem_id = :problemId
                   AND c.ends_at > :bayGio
                   AND NOT (
                        c.starts_at <= :bayGio
                        AND (NOT c.registration_required
                             OR EXISTS (SELECT 1 FROM contest_registrations r
                                         WHERE r.contest_id = c.id
                                           AND r.user_id = :userId))))
            """;

    /**
     * ★ Cùng một luật với {@link #BI_KHOA}, bỏ đúng một mệnh đề: {@code cp.problem_id = :problemId}.
     *
     * <p>Hai câu phải đi cùng nhau. Không ghép được thành một hằng chung — luật ArchUnit 5c
     * cấm mọi phép {@code +} chạm vào text block trong {@code infrastructure}, và cấm có lý
     * do. Nên chúng nằm cạnh nhau ở đây, nơi một người sửa câu này mà quên câu kia sẽ nhìn
     * thấy ngay dòng bên dưới. Đặt chúng ở hai file là đặt một sự lệch vào chỗ khuất.
     *
     * <p>{@code DISTINCT} vì một đề có thể nằm trong nhiều kỳ thi.
     */
    private static final String BI_KHOA_CHO_NGUOI_XEM = """
            SELECT DISTINCT cp.problem_id
              FROM contest_problems cp
              JOIN contests c ON c.id = cp.contest_id
             WHERE c.ends_at > :bayGio
               AND NOT (
                    c.starts_at <= :bayGio
                    AND (NOT c.registration_required
                         OR EXISTS (SELECT 1 FROM contest_registrations r
                                     WHERE r.contest_id = c.id
                                       AND r.user_id = :userId)))
            """;

    /**
     * KHÔNG lọc theo thời gian — xem javadoc của {@code deNamTrongKyThiNaoDo}. Một index scan
     * trên {@code ix_contest_problems_problem}, dừng ở dòng đầu tiên.
     */
    private static final String NAM_TRONG_KY_THI_NAO_DO = """
            SELECT EXISTS (SELECT 1 FROM contest_problems WHERE problem_id = :problemId)
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcContestWindowQuery(@Qualifier("appJdbcClient") JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public OptionalLong contestDangChayChuaDe(long problemId) {
        return jdbc.sql(CONTEST_DANG_CHAY)
                .param("problemId", problemId)
                .param("bayGio", bayGio())
                .query(Long.class)
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    @Override
    public boolean coKyThiDangChay() {
        return Boolean.TRUE.equals(jdbc.sql(CO_KY_THI_DANG_CHAY)
                .param("bayGio", bayGio())
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe) {
        if (laNguoiRaDe) {
            // Ma trận hiển thị, dòng "Đề trong contest chưa mở": SETTER và ADMIN luôn thấy.
            // Trả về sớm để họ không phải trả giá một lượt truy vấn cho một câu đã biết.
            return false;
        }
        return Boolean.TRUE.equals(jdbc.sql(BI_KHOA)
                .param("problemId", problemId)
                .param("bayGio", bayGio())
                // Khách chưa đăng nhập: 0 không khớp users.id nào (GENERATED ALWAYS bắt đầu
                // từ 1), nên EXISTS luôn false — đúng nghĩa "chưa đăng ký".
                .param("userId", userId == null ? 0L : userId)
                .query(Boolean.class)
                .single());
    }

    @Override
    public List<Long> deBiKhoaChoNguoiXem(Long userId) {
        return jdbc.sql(BI_KHOA_CHO_NGUOI_XEM)
                .param("bayGio", bayGio())
                // Cùng quy ước với deBiKhoaBoiLichThi: 0 không khớp users.id nào.
                .param("userId", userId == null ? 0L : userId)
                .query(Long.class)
                .list();
    }

    @Override
    public boolean deNamTrongKyThiNaoDo(long problemId) {
        return Boolean.TRUE.equals(jdbc.sql(NAM_TRONG_KY_THI_NAO_DO)
                .param("problemId", problemId)
                .query(Boolean.class)
                .single());
    }

    private OffsetDateTime bayGio() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
