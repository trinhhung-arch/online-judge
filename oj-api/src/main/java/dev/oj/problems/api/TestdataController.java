package dev.oj.problems.api;

import dev.oj.problems.application.usecase.ImportTestdataUseCase;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * Nạp gói testdata — FR-PROB-03, Bước 4.10.
 *
 * <h2>{@code 202 Accepted}, không phải {@code 200}</h2>
 * Cùng lý do với {@code POST /api/v1/submissions}: khi trả lời, việc <b>chưa xong</b>. Mã 202
 * nói đúng điều đó, và response mang {@code jobId} để client theo dõi tiến độ qua
 * {@code GET /api/v1/jobs/{jobId}}.
 *
 * <p>Trả 200 ở đây là nói dối theo một cách sẽ gây hậu quả thật: người dùng đóng tab, tưởng
 * xong, rồi xuất bản đề bằng một bộ test chưa nạp hết.
 *
 * <p>Không có {@code @RequiresRole} — nó ở trên {@link ImportTestdataUseCase} (bất biến #11).
 */
@RestController
@RequestMapping("/api/v1/problems")
public class TestdataController {

    private final ImportTestdataUseCase importTestdata;

    public TestdataController(ImportTestdataUseCase importTestdata) {
        this.importTestdata = importTestdata;
    }

    @PostMapping(path = "/{problemId}/testdata", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> nap(@PathVariable long problemId,
                                                   @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ProblemsException.khongHopLe("problem.thieu_file", "Chưa chọn gói testdata.");
        }
        try (var in = file.getInputStream()) {
            long jobId = importTestdata.thucHien(problemId, in, file.getSize());
            return ResponseEntity.accepted().body(Map.of("jobId", jobId));
        } catch (IOException e) {
            throw ProblemsException.khongHopLe("problem.tai_len_hong",
                    "Không đọc được file tải lên. Thử lại.");
        }
    }
}
