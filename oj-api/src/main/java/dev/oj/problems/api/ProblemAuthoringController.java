package dev.oj.problems.api;

import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Soạn đề — FR-PROB-01, 07, 08. Bước 4.9.
 *
 * <h2>Vì sao tách khỏi {@link ProblemController}</h2>
 * Không phải vì số dòng. Hai controller trả lời hai câu hỏi khác nhau về <b>khả kiến</b>:
 * cái kia phục vụ đề <i>đã xuất bản</i> cho bất kỳ ai, cái này phục vụ đề <i>của chính mình,
 * kể cả chưa xuất bản</i> cho SETTER. Trộn chúng là để một dòng nhầm lẫn giữa
 * {@code findPublishedById} và {@code findForAuthorById} có thể lộ đề của contest tuần sau.
 *
 * <p>Đường dẫn dùng {@code id} chứ không dùng {@code code}: đề đang soạn có thể chưa có mã ổn
 * định trong đầu tác giả, và mã thì không sửa được sau khi tạo.
 *
 * <p>Không có {@code @RequiresRole} ở đây — nó nằm trên {@link AuthorProblemUseCase}, và một
 * bản sao ở controller chỉ tạo hai chỗ có thể lệch nhau (bất biến #11).
 */
@RestController
@RequestMapping("/api/v1/problems")
public class ProblemAuthoringController {

    private final AuthorProblemUseCase authorProblem;
    private final GetProblemUseCase getProblem;

    public ProblemAuthoringController(AuthorProblemUseCase authorProblem,
                                      GetProblemUseCase getProblem) {
        this.authorProblem = authorProblem;
        this.getProblem = getProblem;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> tao(@RequestBody ProblemAuthoringRequest body) {
        long id = authorProblem.tao(body.toCommand());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("problemId", id));
    }

    @PutMapping("/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sua(@PathVariable long problemId, @RequestBody ProblemAuthoringRequest body) {
        authorProblem.sua(problemId, body.toCommand());
    }

    /** Đọc đề của chính mình để sửa — thấy cả {@code DRAFT}. */
    @GetMapping("/{problemId}/edit")
    public ProblemResponse doc(@PathVariable long problemId) {
        var de = authorProblem.doc(problemId);
        return ProblemResponse.from(de, getProblem.html(de));
    }

    /** FR-PROB-08. Từ chối nếu đề chưa có testdata — xem {@link AuthorProblemUseCase#xuatBan}. */
    @PostMapping("/{problemId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void xuatBan(@PathVariable long problemId) {
        authorProblem.xuatBan(problemId);
    }

    @PostMapping("/{problemId}/retire")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void goXuong(@PathVariable long problemId) {
        authorProblem.goXuong(problemId);
    }
}
