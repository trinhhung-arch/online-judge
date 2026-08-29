package dev.oj.problems.application.usecase;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.StatementService;
import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemNotFoundException;
import dev.oj.problems.domain.ProblemRules;
import dev.oj.problems.domain.ProblemStatus;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/**
 * Soạn đề: tạo · sửa · xuất bản · gỡ xuống — FR-PROB-01, 07, 08. Bước 4.9.
 *
 * <h2>Bốn thao tác trong một use-case, cố ý</h2>
 * Chúng dùng chung đúng một tập bất biến ({@link ProblemRules}) và đúng một phép kiểm quyền
 * (SETTER, và điều kiện chủ sở hữu nằm trong câu query). Tách thành bốn class là nhân bốn
 * phần khai báo phụ thuộc để tiết kiệm không gì cả — và tạo bốn cơ hội để một trong bốn quên
 * gọi một phép kiểm.
 *
 * <h2>★ Quyền sở hữu KHÔNG kiểm ở đây</h2>
 * Không có dòng nào so {@code problem.ownerId()} với người gọi. Điều kiện
 * {@code (:laAdmin OR owner_id = :requesterId)} nằm trong <b>câu query</b>
 * ({@code JdbcProblemRepository}), nên một câu {@code UPDATE} không khớp trả về 0 dòng và ta
 * ném {@code NOT_FOUND}. Đó là Bước 4.8 áp dụng đúng chỗ: <i>"query sai chỗ là lỗ hổng ngay
 * cả khi câu if viết đúng"</i>.
 *
 * <p>404 chứ không 403, cả khi đề có thật: 403 xác nhận đề đó tồn tại, và đề chưa xuất bản
 * rất thường là đề của contest tuần sau (FR-PROB-08).
 */
@RequiresRole(Role.SETTER)
@Service
public class AuthorProblemUseCase {

    private final CurrentUserProvider currentUser;
    private final ProblemAuthoringRepository problems;
    private final AuditLog auditLog;
    private final Clock clock;

    public AuthorProblemUseCase(CurrentUserProvider currentUser, ProblemAuthoringRepository problems,
                                AuditLog auditLog, Clock clock) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** FR-PROB-01. Đề mới luôn ở {@code DRAFT} — không có tham số nào đổi được điều đó. */
    public long tao(Command lenh) {
        lenh.kiemTra(true);
        long ownerId = currentUser.current().id();
        long id = problems.taoMoi(new ProblemAuthoringRepository.NewProblem(
                lenh.code().trim(), lenh.title().trim(), lenh.statementMd(),
                StatementService.bam(lenh.statementMd()),
                lenh.timeLimitMs(), lenh.memoryLimitKb(),
                lenh.checkerType(), lenh.checkerEpsilon(),
                lenh.scoringMode(), lenh.feedbackLevel(), ownerId));
        auditLog.ghi("PROBLEM_CREATED", "problem", id, Map.of("code", lenh.code().trim()));
        return id;
    }

    /** FR-PROB-01 và FR-PROB-07. Mã đề không sửa được — xem {@code ProblemEdit}. */
    public void sua(long problemId, Command lenh) {
        lenh.kiemTra(false);
        var nguoiGoi = currentUser.current();
        boolean daSua = problems.capNhat(problemId, new ProblemAuthoringRepository.ProblemEdit(
                        lenh.title().trim(), lenh.statementMd(),
                        StatementService.bam(lenh.statementMd()),
                        lenh.timeLimitMs(), lenh.memoryLimitKb(),
                        lenh.checkerType(), lenh.checkerEpsilon(),
                        lenh.scoringMode(), lenh.feedbackLevel(), lenh.allowPublicSolutions()),
                nguoiGoi.id(), nguoiGoi.isAdmin());
        if (!daSua) {
            throw ProblemNotFoundException.byId(problemId);
        }
        auditLog.ghi("PROBLEM_UPDATED", "problem", problemId,
                Map.of("feedbackLevel", lenh.feedbackLevel().name()));
    }

    /**
     * FR-PROB-08 — xuất bản.
     *
     * <p>Từ chối nếu đề chưa có testdata. Một đề {@code PUBLISHED} với
     * {@code current_testdata_version = 0} sẽ nhận bài nộp và cho <b>IE trên mọi bài</b> —
     * người dùng thấy hệ thống hỏng, còn tác giả thì không biết mình quên gì.
     */
    public void xuatBan(long problemId) {
        Problem de = doc(problemId);
        if (de.currentTestdataVersion() <= 0) {
            throw ProblemsException.chuaCoTestdata();
        }
        doiTrangThai(problemId, ProblemStatus.PUBLISHED);
    }

    /** FR-PROB-08 — gỡ xuống. Bài nộp cũ và bảng xếp hạng giữ nguyên (xem {@link ProblemStatus}). */
    public void goXuong(long problemId) {
        doc(problemId);
        doiTrangThai(problemId, ProblemStatus.RETIRED);
    }

    /** Đọc đề của chính mình, <b>kể cả khi chưa xuất bản</b>. */
    public Problem doc(long problemId) {
        var nguoiGoi = currentUser.current();
        return problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));
    }

    private void doiTrangThai(long problemId, ProblemStatus moi) {
        var nguoiGoi = currentUser.current();
        if (!problems.doiTrangThai(problemId, moi, nguoiGoi.id(), nguoiGoi.isAdmin(),
                clock.instant())) {
            throw ProblemNotFoundException.byId(problemId);
        }
        auditLog.ghi("PROBLEM_STATUS_CHANGED", "problem", problemId,
                Map.of("status", moi.name()));
    }

    /**
     * Đầu vào của tạo và sửa.
     *
     * @param code chỉ dùng khi tạo; khi sửa thì bị bỏ qua, vì mã đề xuất hiện trong mọi liên
     *             kết đã chia sẻ và trong {@code audit_log}
     */
    public record Command(
            String code, String title, String statementMd,
            int timeLimitMs, int memoryLimitKb,
            CheckerType checkerType, BigDecimal checkerEpsilon,
            ScoringMode scoringMode, FeedbackLevel feedbackLevel,
            boolean allowPublicSolutions) {

        void kiemTra(boolean laTaoMoi) {
            if (laTaoMoi) {
                ProblemRules.kiemTraMaDe(code);
            }
            ProblemRules.kiemTraTieuDe(title);
            ProblemRules.kiemTraDeBai(statementMd);
            ProblemRules.kiemTraGioiHan(timeLimitMs, memoryLimitKb);
            ProblemRules.kiemTraChecker(checkerType, checkerEpsilon);
            if (scoringMode == null || feedbackLevel == null || checkerType == null) {
                throw ProblemsException.khongHopLe("problem.thieu_truong",
                        "Thiếu kiểu checker, cách tính điểm hoặc mức phản hồi.");
            }
        }
    }
}
