package dev.oj.problems.application.port;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Đường soạn đề của SETTER/ADMIN — FR-PROB-01, 07, 08. Bước 4.9.
 *
 * <h2>★ Vì sao đây là một port RIÊNG chứ không phải thêm hàm vào {@link ProblemRepository}</h2>
 * Port kia có một tính chất được ghi thành lời từ M1: <i>mọi phương thức đều có chữ
 * {@code Published} trong tên</i>, để việc quên lọc trạng thái trở nên khó xảy ra. Thêm một
 * {@code findForAuthor} vào đó là xoá đúng cái tính chất ấy — và từ đó một lần gọi nhầm hàm
 * sẽ lộ đề {@code DRAFT}, thứ rất thường là đề của contest tuần sau (FR-PROB-08).
 *
 * <p>Tách ra thì mỗi port giữ được <b>một</b> bất biến đọc được từ tên hàm: kia là "chỉ đề đã
 * xuất bản", đây là "chỉ đề của chính mình". Và {@code SubmitSolutionUseCase} — đường nóng —
 * chỉ tiêm port kia, nên nó <i>không có cách nào</i> chạm tới một đề chưa xuất bản.
 *
 * <h2>Mọi phương thức mang {@code requesterId} và {@code laAdmin}</h2>
 * Không phải để use-case kiểm sau khi đọc, mà để điều kiện chủ sở hữu nằm <b>trong câu
 * query</b> ({@code oj-api/CLAUDE.md} mục 2, Bước 4.8). Đề của người khác trả về rỗng, và
 * use-case ném {@code NOT_FOUND} một cách tự nhiên.
 */
public interface ProblemAuthoringRepository {

    /** Đọc một đề <b>kể cả khi chưa xuất bản</b> — chỉ tác giả và ADMIN. */
    Optional<Problem> findForAuthor(String code, long requesterId, boolean laAdmin);

    Optional<Problem> findForAuthorById(long id, long requesterId, boolean laAdmin);

    /** @return {@code problems.id} vừa tạo */
    long taoMoi(NewProblem problem);

    /** @return {@code false} nếu đề không tồn tại hoặc không thuộc người gọi */
    boolean capNhat(long id, ProblemEdit sua, long requesterId, boolean laAdmin);

    /**
     * FR-PROB-08 — xuất bản, gỡ xuống.
     *
     * <p>{@code publishedAt} chỉ đặt lần đầu ({@code COALESCE}): {@code ck_problems_published}
     * đòi nó khác NULL khi {@code PUBLISHED}, và một đề gỡ rồi đăng lại không phải một đề mới.
     */
    /**
     * Đề này đã có bài nộp nào chưa — chốt của {@code xoa}.
     *
     * <p>Câu này chạm {@code submissions}, bảng của module {@code judging}. Luật ArchUnit 3
     * nói về phụ thuộc giữa các <b>package Java</b>, không về SQL, nên đây không phải vi
     * phạm — cùng lý do và cùng tiền lệ với cột {@code daGiai} ở {@code JdbcProblemRepository}.
     */
    boolean coBaiNop(long problemId);

    /**
     * Xoá hẳn một đề. Chỉ gọi sau khi đã chắc không có bài nộp và không thuộc kỳ thi nào.
     *
     * <p>{@code problem_tags}, {@code testdata_versions} và {@code testcases} đi theo bằng
     * {@code ON DELETE CASCADE}. Hai khoá ngoại còn lại — {@code submissions} và
     * {@code contest_problems} — <b>cố ý không cascade</b>, nên database vẫn là chốt cuối
     * nếu chốt ở use-case bị đi vòng.
     *
     * @return {@code false} nếu không có dòng nào bị xoá — đề không tồn tại, hoặc người gọi
     *         không phải chủ đề. Hai trường hợp đó KHÔNG được phân biệt ra ngoài.
     */
    boolean xoa(long id, long requesterId, boolean laAdmin);

    boolean doiTrangThai(long id, ProblemStatus moi, long requesterId, boolean laAdmin,
                         Instant luc);

    /** Đầu vào tạo đề — FR-PROB-01. */
    /**
     * @param allowPublicSolutions phải có mặt ở đây dù {@link ProblemEdit} cũng có nó. Bản đầu
     *                             chỉ để nó ở đường SỬA, nên một đề tạo ra với cờ bật vẫn nằm
     *                             ở {@code false} cho tới lần lưu đầu tiên — và không có gì
     *                             báo, vì cột mang {@code DEFAULT FALSE}
     */
    record NewProblem(
            String code, String title, String statementMd, String statementHash,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            long ownerId, boolean allowPublicSolutions) {
    }

    /**
     * Đầu vào sửa đề — FR-PROB-01, 07.
     *
     * <p><b>Không có {@code code}, {@code ownerId}, {@code status},
     * {@code currentTestdataVersion}.</b> Mã đề xuất hiện trong mọi liên kết đã chia sẻ và
     * trong {@code audit_log}; chủ sở hữu và trạng thái có đường đi riêng; phiên bản testdata
     * chỉ đổi qua job nạp dữ liệu. Record hẹp thì không có trường nào để gửi thừa.
     */
    record ProblemEdit(
            String title, String statementMd, String statementHash,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            boolean allowPublicSolutions) {
    }
}
