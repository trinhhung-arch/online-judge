package dev.oj.problems.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.PublicAccess;
import dev.oj.problems.application.StatementService;
import dev.oj.problems.application.port.ProblemRepository;

import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Đọc một đề đã xuất bản theo mã — FR-PROB-01, đường phục vụ {@code GET /api/v1/problems/{code}}.
 *
 * <p>Ngắn đến mức trông như thừa. Nó không thừa: đây là chỗ mà M4 và M5 sẽ mọc thêm việc
 * (kiểm khung giờ contest, quyền của SETTER với đề chưa xuất bản, đọc bản render đã cache),
 * và nếu controller gọi thẳng repository thì những việc đó sẽ mọc ở controller — tức là ở
 * đúng chỗ mà một request API trực tiếp đi vòng qua được (bất biến #11).
 *
 * <p><b>Lời tiên đoán đó đã thành sự thật ba lần</b>: cache bản render ở M4 Bước 4.9, và chốt
 * khung giờ contest ở M5 Bước 5.4 ({@link #kiemLichThi}). Cả ba mọc ở đây, không ở controller.
 *
 * <h2>★ FR-CON-03 — đề của kỳ thi chỉ mở trong khung giờ</h2>
 * Chốt nằm ở use-case, <b>không phải ở việc ẩn nút</b>. Một trang không hiện link tới đề vẫn
 * để lộ đề cho bất kỳ ai gõ thẳng {@code /api/v1/problems/&lt;mã&gt;} — và với đề của kỳ thi
 * tuần sau, "bất kỳ ai" bao gồm cả thí sinh.
 */
@PublicAccess("Khách xem được đề đã xuất bản — ô đầu tiên của ma trận hiển thị, oj-api/CLAUDE.md mục 2. Đề CHƯA xuất bản bị lọc trong câu query, không phải ở đây.")
@Service
public class GetProblemUseCase {

    private final StatementService statements;

    private final ProblemRepository problems;
    private final ContestWindowQuery lichThi;
    private final CurrentUserProvider currentUser;

    /**
     * Bản render HTML kèm cache — FR-PROB-02. Xem {@link StatementService}.
     *
     * <p>{@code null} nếu đề không tồn tại; người gọi đã ném {@code NOT_FOUND} trước đó.
     */
    public String html(dev.oj.problems.domain.Problem de) {
        return statements.html(StatementService.bam(de.statementMd()), de.statementMd());
    }

    public GetProblemUseCase(ProblemRepository problems, StatementService statements,
                             ContestWindowQuery lichThi, CurrentUserProvider currentUser) {
        this.statements = statements;
        this.problems = problems;
        this.lichThi = lichThi;
        this.currentUser = currentUser;
    }

    /**
     * @param code mã đề, không phân biệt hoa thường
     * @throws ProblemNotFoundException nếu không có, chưa xuất bản, hoặc mã sai định dạng —
     *         cùng một câu chữ cho cả ba, xem javadoc của lớp ngoại lệ đó
     */
    public Problem byCode(String code) {
        // Kiểm định dạng TRƯỚC khi hỏi DB. Không phải để tối ưu — mà để một chuỗi 4KB rác
        // trên đường /problems/{code} không thành một câu query và một dòng log dài 4KB.
        if (!Problem.isValidCode(code)) {
            throw ProblemNotFoundException.byCode(code);
        }
        Problem de = problems.findPublishedByCode(code)
                .orElseThrow(() -> ProblemNotFoundException.byCode(code));
        kiemLichThi(de);
        return de;
    }

    /**
     * Dùng trên đường nộp bài: {@code SubmitSolutionUseCase} cần giới hạn và
     * {@code current_testdata_version} để đóng dấu vào bài nộp.
     *
     * @throws ProblemNotFoundException kể cả khi đề tồn tại nhưng chưa có testdata — với
     *         người nộp bài thì hai trường hợp đó không khác nhau
     */
    public Problem submittableById(long id) {
        Problem problem = problems.findPublishedById(id)
                .orElseThrow(() -> ProblemNotFoundException.byId(id));
        if (!problem.hasTestdata()) {
            throw ProblemNotFoundException.noTestdata(id);
        }
        kiemLichThi(problem);
        return problem;
    }

    /**
     * FR-CON-03 — Bước 5.4.
     *
     * <p>Ném {@code ProblemNotFoundException} chứ không một ngoại lệ riêng của contest: người
     * bị từ chối <b>không được biết vì sao</b>. "Đề này thuộc một kỳ thi chưa mở" là đủ để
     * biết có một kỳ thi sắp diễn ra và nó gồm những đề nào — thông tin mà một thí sinh không
     * nên có trước giờ thi.
     *
     * <p>Người gọi là tác giả đề hoặc ADMIN thì bỏ qua chốt này (ma trận hiển thị,
     * {@code oj-api/CLAUDE.md} mục 2). Người chưa đăng nhập đi tiếp với {@code userId = null}.
     */
    private void kiemLichThi(Problem de) {
        Long userId = null;
        boolean laNguoiRaDe = false;
        try {
            var nguoiGoi = currentUser.current();
            userId = nguoiGoi.id();
            laNguoiRaDe = nguoiGoi.isAdmin() || nguoiGoi.id() == de.ownerId();
        } catch (AuthorizationException e) {
            // Khách chưa đăng nhập. Không phải lỗi — trang đề là trang công khai, và câu hỏi
            // "có được vào không" vẫn trả lời được với userId null.
            userId = null;
        }
        if (lichThi.deBiKhoaBoiLichThi(de.id(), userId, laNguoiRaDe)) {
            throw ProblemNotFoundException.byId(de.id());
        }
    }

    // -------------------------------------------------------------------------
    // Mọc thêm ở đây, không mọc ở controller:
    //
    //   M4  đề DRAFT cho tác giả và ADMIN (FR-PROB-08) -> thêm nhánh findForAuthor,
    //       và trả bản render đã cache thay cho statementMd (FR-PROB-02).
    //
    //   M5  FR-CON-03 "đề của contest chỉ truy cập được trong khung giờ" — hỏi qua
    //       ContestWindowQuery đặt ở dev.oj.platform, KHÔNG import dev.oj.contests
    //       (luật ArchUnit 3 cấm problems -> contests, và cấm có lý do).
    //       Kiểm ở ĐÂY, không phải ẩn nút trên UI: một request API trực tiếp bỏ qua UI
    //       là chuyện 5 phút (frplan.md FR-CON-03).
    // -------------------------------------------------------------------------
}
