package dev.oj.problems.application.usecase;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.application.port.TestdataRepository;
import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * ★★ FR-PROB-12 — tải gói testdata về. <b>Đây là endpoint nguy hiểm nhất hệ thống.</b>
 *
 * <h2>Đọc trước khi sửa một dòng nào ở đây</h2>
 * Mọi quy tắc khác của dự án đều hướng tới việc nội dung testcase ẩn <i>không rời khỏi
 * worker</i> (bất biến #1). File này là <b>ngoại lệ duy nhất được phép tồn tại</b>, và nó tồn
 * tại vì một lý do hẹp: người ra đề phải lấy lại được bộ test của chính mình — nếu không,
 * một lần mất máy là mất luôn công sức làm đề.
 *
 * <p>Vì là ngoại lệ duy nhất, nó phải được viết như một ngoại lệ:
 *
 * <ol>
 *   <li><b>Điều kiện chủ sở hữu nằm TRONG câu query.</b> {@code findForAuthorById} nhận
 *       {@code ownerId} và {@code laAdmin} làm tham số SQL. Không có câu {@code if} nào ở đây
 *       kiểm quyền — một câu {@code if} viết đúng vẫn là lỗ hổng nếu ai đó thêm một đường đọc
 *       thứ hai và quên nó ({@code oj-api/CLAUDE.md} mục 2).</li>
 *   <li><b>Người nộp bài không bao giờ vào được</b>, kể cả tác giả của một bài vừa fail. Đó là
 *       ô quan trọng nhất của ma trận hiển thị, và {@code frplan.md} mục 3.1 giải thích vì sao:
 *       nộp sai từng test một là một <i>thuật toán rút trích</i> toàn bộ bộ test.</li>
 *   <li><b>Mỗi lượt tải ghi {@code audit_log}.</b> Không phải để trừng phạt ai — mà để câu hỏi
 *       "bộ test này rò rỉ từ đâu" có câu trả lời. Một đường ra dữ liệu không có nhật ký là
 *       một đường ra không điều tra được.</li>
 *   <li><b>Trả về nguyên gói ZIP theo hash</b>, không phải từng file theo tên. Kho là
 *       content-addressed, nên khoá là {@code manifest_sha256} — không có tham số đường dẫn
 *       nào để mà đi ngang thư mục.</li>
 * </ol>
 *
 * <h2>Vì sao {@code @RequiresRole(SETTER)} là chốt yếu hơn nó trông</h2>
 * Nó chỉ nói "người gọi là SETTER hoặc ADMIN". Nó <b>không</b> nói "SETTER của đề này". Chốt
 * thật là câu query ở dưới. Ghi ra đây vì đọc lướt qua annotation rất dễ tưởng đã đủ.
 */
@RequiresRole(Role.SETTER)
@Service
public class DownloadTestdataUseCase {

    private static final Logger log = LoggerFactory.getLogger(DownloadTestdataUseCase.class);

    private final CurrentUserProvider currentUser;
    private final ProblemAuthoringRepository problems;
    private final TestdataRepository testdata;
    private final TestdataStore store;
    private final AuditLog auditLog;

    public DownloadTestdataUseCase(CurrentUserProvider currentUser,
                                   ProblemAuthoringRepository problems,
                                   TestdataRepository testdata, TestdataStore store,
                                   AuditLog auditLog) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.testdata = testdata;
        this.store = store;
        this.auditLog = auditLog;
    }

    /**
     * @param version {@code null} = phiên bản đang hoạt động
     * @return luồng ZIP; <b>người gọi phải đóng</b>
     * @throws ProblemNotFoundException nếu đề không tồn tại, không thuộc người gọi, hoặc chưa
     *         có phiên bản testdata nào — cả ba cho cùng một 404, cùng một câu chữ. 403 ở đây
     *         xác nhận "đề này tồn tại và có testdata", và với một đề của kỳ thi tuần sau thì
     *         chính điều đó là thứ không được lộ
     */
    public GoiTestdata tai(long problemId, Integer version) {
        var nguoiGoi = currentUser.current();

        // ★ Chốt thật nằm ở ĐÂY, trong câu query — không phải ở @RequiresRole phía trên.
        problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));

        String sha = testdata.shaCuaPhienBan(problemId, version)
                .orElseThrow(() -> ProblemNotFoundException.noTestdata(problemId));

        // Ghi audit TRƯỚC khi mở luồng. Nếu ghi sau, một lần hỏng ở tầng kho sẽ để lại một
        // lượt tải không có dấu vết — và với đúng endpoint này thì "không có dấu vết" là
        // trạng thái tệ nhất có thể.
        Map<String, Object> chiTiet = new HashMap<>();
        chiTiet.put("version", version == null ? "hiện hành" : version);
        chiTiet.put("sha256", sha);
        auditLog.ghi("PROBLEM_TESTDATA_DOWNLOADED", "problem", problemId, chiTiet);
        log.info("SETTER {} tải testdata đề {} (phiên bản {})",
                nguoiGoi.id(), problemId, chiTiet.get("version"));

        return new GoiTestdata(sha, store.doc(sha));
    }

    /**
     * @param noiDung luồng từ kho. <b>Không đọc nó vào bộ nhớ</b> ở bất cứ đâu trên đường ra:
     *                gói có thể tới 200MB, và một lượt tải làm 200MB heap là một lượt tải làm
     *                sập API — tức là một cách gây sự cố mà chỉ cần một tài khoản SETTER
     */
    public record GoiTestdata(String sha256, InputStream noiDung) {

        /** Tên file gợi ý. Chỉ có hash — không lộ mã đề, không lộ tiêu đề. */
        public String tenFile() {
            return "testdata-" + sha256.substring(0, 12) + ".zip";
        }
    }
}
