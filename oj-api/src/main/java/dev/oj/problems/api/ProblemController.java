package dev.oj.problems.api;

import dev.oj.platform.config.AppProperties;
import dev.oj.platform.web.CursorPage;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/problems/{code}} — FR-PROB-01.
 *
 * <h2>⚠️ Tiền tố {@code /api/v1} viết ĐẦY ĐỦ ở đây, không dùng context-path</h2>
 * Bản năng là đặt {@code server.servlet.context-path: /api/v1} rồi viết
 * {@code @RequestMapping("/problems")}. <b>Đừng.</b> {@code context-path} bọc <i>toàn bộ</i>
 * ứng dụng, nên hai endpoint {@code /internal/judge/*} cũng bị đẩy thành
 * {@code /api/v1/internal/judge/*} — và lúc đó chúng nằm chung tiền tố với phần công khai,
 * tức là lộ ra Cloudflare Tunnel cùng nhau. Đó đúng là thứ {@code oj-api/CLAUDE.md} mục 5 cấm.
 *
 * <p>Đổi lại, mỗi controller công khai phải tự mang tiền tố đầy đủ. Rẻ, và nhìn vào
 * {@code @RequestMapping} là biết ngay đường dẫn thật.
 *
 * <h2>Controller mỏng đến mức nhàm chán, và đó là yêu cầu</h2>
 * Nó chỉ làm ba việc: nhận tham số, gọi use-case, đổi domain sang DTO. Nó <b>không</b>:
 *
 * <ul>
 *   <li><b>Kiểm quyền.</b> Bất biến #11 — kiểm ở use-case. Controller là chỗ dễ đi vòng nhất:
 *       một request API trực tiếp bỏ qua UI là chuyện 5 phút, và ở M5 thì "đề của contest chỉ
 *       mở trong khung giờ" mà kiểm ở đây nghĩa là ai cũng xem được đề trước giờ thi.</li>
 *   <li><b>Bắt ngoại lệ.</b> {@code GlobalExceptionHandler} lo. Một khối {@code try/catch}
 *       ở controller gần như luôn kết thúc bằng việc trả {@code e.getMessage()} ra client —
 *       tức là câu chữ chưa ai duyệt xem nó lộ gì.</li>
 *   <li><b>Chạm DB.</b> Luật ArchUnit 5a chặn {@code org.springframework.jdbc} ngoài
 *       {@code infrastructure}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/problems")
public class ProblemController {

    private final GetProblemUseCase getProblem;
    private final ListProblemsUseCase listProblems;
    private final AppProperties properties;

    public ProblemController(GetProblemUseCase getProblem, ListProblemsUseCase listProblems,
                             AppProperties properties) {
        this.getProblem = getProblem;
        this.listProblems = listProblems;
        this.properties = properties;
    }

    /**
     * Tra theo mã đề, không phân biệt hoa thường.
     *
     * <p>Dùng {@code code} chứ không dùng {@code id} trên đường dẫn công khai: mã đề là thứ
     * người ta gõ và chia sẻ ({@code /problems/A-PLUS-B}), còn {@code id} tuần tự thì mời gọi
     * việc dò từ 1 lên. Không phải một biện pháp bảo mật — câu query đã lọc trạng thái rồi —
     * nhưng là một đường dẫn dùng được cho con người.
     *
     * <p>Không phân trang vì trả về đúng một bản ghi. Danh sách đề (FR-PROB-09, phân trang 50)
     * là M4.
     */
    @GetMapping("/{code}")
    public ProblemResponse byCode(@PathVariable String code) {
        var de = getProblem.byCode(code);
        return ProblemResponse.from(de, getProblem.html(de));
    }

    /**
     * FR-PROB-09 — danh sách đề đã xuất bản. Bước 4.9.
     *
     * <p>Bất biến #8: có {@code LIMIT}, phân trang cursor, và xin 1000 thì trả
     * {@code max-size} chứ không trả lỗi ({@code oj-api/CLAUDE.md} mục 3).
     */
    @GetMapping
    public CursorPage<ProblemSummaryResponse> danhSach(
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) Boolean daGiai,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {

        int gioiHan = Math.min(
                size == null ? properties.page().defaultSize() : Math.max(1, size),
                properties.page().maxSize());
        var trang = listProblems.thucHien(tag, daGiai, cursor, gioiHan);
        return new CursorPage<>(
                trang.items().stream().map(ProblemSummaryResponse::from).toList(),
                trang.nextCursor());
    }

    // -------------------------------------------------------------------------
    // M4 thêm vào controller này:
    //   GET  /problems               FR-PROB-09 — lọc theo tag/độ khó/đã giải,
    //                                phân trang cursor, trần 50 (bất biến #8).
    //                                Trả CursorPage<ProblemSummaryResponse>, KHÔNG trả List.
    //   POST /problems               FR-PROB-01 tạo đề — @RequiresRole(SETTER)
    //   PUT  /problems/{code}        FR-PROB-11 cấm sửa khi đề đang trong contest đang chạy
    //
    // Endpoint tải testdata (FR-PROB-12) KHÔNG bao giờ đặt ở controller này: nó chỉ dành cho
    // SETTER của chính đề đó và ADMIN, và trộn nó vào cùng một class với endpoint công khai
    // là cách nhanh nhất để một ngày nào đó nó thừa hưởng nhầm cấu hình.
    // -------------------------------------------------------------------------
}
