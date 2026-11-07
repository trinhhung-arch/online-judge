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
