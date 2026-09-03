package dev.oj.problems.api;

import dev.oj.problems.application.usecase.ImportTestdataUseCase;
import dev.oj.problems.domain.ProblemsException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import dev.oj.problems.application.usecase.DownloadTestdataUseCase;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
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
    private final DownloadTestdataUseCase downloadTestdata;

    public TestdataController(ImportTestdataUseCase importTestdata,
                              DownloadTestdataUseCase downloadTestdata) {
        this.importTestdata = importTestdata;
        this.downloadTestdata = downloadTestdata;
    }

    /**
     * ★★ FR-PROB-12 — Bước 6.14. Đọc javadoc của {@link DownloadTestdataUseCase} trước khi
     * sửa bất cứ thứ gì ở đây; đây là đường ra duy nhất của nội dung testcase ẩn.
     *
     * <p>{@code InputStreamResource} chứ không phải {@code byte[]}: Spring stream thẳng ra
     * socket, nên một gói 200MB không đi qua heap. Trả {@code byte[]} ở đây là biến một tài
     * khoản SETTER thành một cách làm sập API.
     */
    @GetMapping("/{problemId}/testdata")
    public ResponseEntity<InputStreamResource> tai(@PathVariable long problemId,
                                                   @RequestParam(required = false) Integer version) {
        DownloadTestdataUseCase.GoiTestdata goi = downloadTestdata.tai(problemId, version);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + goi.tenFile() + "\"")
                .body(new InputStreamResource(goi.noiDung()));
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
