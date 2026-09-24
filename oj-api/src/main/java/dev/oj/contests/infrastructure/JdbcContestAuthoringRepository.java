package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestsException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Hiện thực {@link ContestAuthoringRepository}. Pool {@code app}.
 *
 * <p>Mọi câu ở đây mang {@code (created_by = :nguoiGoi OR :laAdmin)} — và {@link #THEM_DE}
 * mang thêm {@code (owner_id = :nguoiGoi OR :laAdmin)} của đề. Hai điều kiện nằm trong CÙNG
 * câu ghi, không phải một câu {@code SELECT} kiểm trước rồi ghi sau: kiểm-rồi-ghi là hai
 * bước, và điều kiện sở hữu thì phải là một ({@code oj-api/CLAUDE.md} mục 2).
 */
@Repository
public class JdbcContestAuthoringRepository implements ContestAuthoringRepository {

    private static final String CHON_DE_SOAN = """
            SELECT id, slug, title, format, starts_at, ends_at, freeze_at, unfrozen_at,
                   penalty_minutes, registration_required, reveal_after_end, created_by
              FROM contests
             WHERE id = :contestId
               AND (created_by = :nguoiGoi OR :laAdmin)
            """;

    /**
     * {@code INSERT ... SELECT ... WHERE} thay cho {@code VALUES}: không đủ quyền thì
     * {@code SELECT} ra 0 dòng và câu lệnh ghi 0 dòng. {@code ON CONFLICT DO UPDATE} (gắn lại
     * = đổi nhãn/điểm) chỉ chạy trên dòng đã qua được {@code WHERE}, nên đường đổi nhãn cũng
     * không đi vòng được.
     *
     * <p>{@code CAST} ở danh sách chọn: trong {@code INSERT ... SELECT}, Postgres dựng kiểu
     * của {@code SELECT} trước rồi mới so với cột đích — không có gì để suy ra kiểu của một
     * tham số trần.
     */
    private static final String THEM_DE = """
            INSERT INTO contest_problems (contest_id, problem_id, label, points,
                                          created_for_contest)
            SELECT CAST(:contestId AS bigint), CAST(:problemId AS bigint), CAST(:label AS text),
                   CAST(:points AS integer), CAST(:soanRieng AS boolean)
             WHERE EXISTS (SELECT 1 FROM contests c
                            WHERE c.id = :contestId
                              AND (c.created_by = :nguoiGoi OR :laAdmin))
               AND EXISTS (SELECT 1 FROM problems p
                            WHERE p.id = :problemId
                              AND (p.owner_id = :nguoiGoi OR :laAdmin))
            ON CONFLICT (contest_id, problem_id) DO UPDATE
               SET label = EXCLUDED.label,
                   points = EXCLUDED.points,
                   -- Nguồn gốc DÍNH: gắn lại một đề đã soạn riêng không biến nó thành đề
                   -- mượn, và ngược lại. Đây là dữ kiện lịch sử, không phải một thuộc tính
                   -- người dùng chỉnh được bằng cách bấm lại nút.
                   created_for_contest = contest_problems.created_for_contest
                                      OR EXCLUDED.created_for_contest
            """;

    private static final String GO_DE = """
            DELETE FROM contest_problems cp
             WHERE cp.contest_id = :contestId
               AND cp.problem_id = :problemId
               AND EXISTS (SELECT 1 FROM contests c
                            WHERE c.id = cp.contest_id
                              AND (c.created_by = :nguoiGoi OR :laAdmin))
            """;

    private final JdbcClient jdbc;

    public JdbcContestAuthoringRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Contest> timDeSoan(long contestId, long nguoiGoi, boolean laAdmin) {
        return jdbc.sql(CHON_DE_SOAN)
                .param("contestId", contestId)
                .param("nguoiGoi", nguoiGoi)
                .param("laAdmin", laAdmin)
                .query(JdbcContestRepository.MAPPER)
                .optional();
    }

    @Override
    public void themDe(long contestId, long problemId, String label, int points,
                       long nguoiGoi, boolean laAdmin) {
        gan(contestId, problemId, label, points, false, nguoiGoi, laAdmin);
    }

    @Override
    public void themDeSoanRieng(long contestId, long problemId, String label, int points,
                                long nguoiGoi, boolean laAdmin) {
        gan(contestId, problemId, label, points, true, nguoiGoi, laAdmin);
    }

    private void gan(long contestId, long problemId, String label, int points,
                     boolean soanRieng, long nguoiGoi, boolean laAdmin) {
        int soDong;
        try {
            soDong = jdbc.sql(THEM_DE)
                    .param("contestId", contestId)
                    .param("problemId", problemId)
                    .param("label", label)
                    .param("points", points)
                    .param("soanRieng", soanRieng)
                    .param("nguoiGoi", nguoiGoi)
                    .param("laAdmin", laAdmin)
                    .update();
        } catch (DuplicateKeyException e) {
            // UNIQUE (contest_id, label) — hai đề cùng nhãn 'A' thì bảng xếp hạng có hai cột
            // trùng tên và không ai biết cột nào là đề nào.
            throw ContestsException.khongHopLe("contest.nhan_de_trung",
                    "Nhãn đề này đã được dùng trong kỳ thi.");
        } catch (DataIntegrityViolationException e) {
            // Khoá ngoại problem_id: chỉ còn tới được đây khi đề bị xoá giữa câu EXISTS và
            // lúc ghi. Cùng câu với nhánh 0 dòng dưới — với người gọi đó là một chuyện.
            throw deKhongGanDuoc(problemId);
        }
        if (soDong == 0) {
            throw deKhongGanDuoc(problemId);
        }
    }

    /**
     * ★ Đề không có thật và đề của người khác cho CÙNG một câu. Nói "đề này là của người
     * khác" là xác nhận id ấy tồn tại — và dò id tuần tự là cách lấy danh sách đề DRAFT.
     *
     * <p>Vẫn nhắc lại con số người dùng gõ: ca thật đã gặp là người ra đề gõ MÃ đề
     * ({@code A-PLUS-B}, đọc từ trang danh sách) vào ô nhận ID — câu này phải chỉ ra được
     * sự nhầm lẫn ấy ({@code ContestProblemsIT}).
     */
    private static ContestsException deKhongGanDuoc(long problemId) {
        return ContestsException.khongHopLe("contest.de_khong_ton_tai",
                "Không có đề nào mang id " + problemId + " trong số đề bạn được gắn vào kỳ thi.");
    }

    @Override
    public boolean goDe(long contestId, long problemId, long nguoiGoi, boolean laAdmin) {
        return jdbc.sql(GO_DE)
                .param("contestId", contestId)
                .param("problemId", problemId)
                .param("nguoiGoi", nguoiGoi)
                .param("laAdmin", laAdmin)
                .update() == 1;
    }
}
