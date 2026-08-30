package dev.oj.problems.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.RequiresRole;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.domain.ProblemNotFoundException;
import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Nhận gói testdata và <b>xếp một job nền</b> — FR-PROB-03, Bước 4.10.
 *
 * <h2>Use-case này KHÔNG nạp testdata, nó chỉ nhận file rồi trả về ngay</h2>
 * Cùng khuôn với {@code SubmitSolutionUseCase}: <i>accept ≠ process</i>. Việc thật do
 * {@code TestdataImportJob} làm, và người gọi nhận về một {@code jobId} để theo dõi tiến độ.
 *
 * <p>Đó không phải sự cầu kỳ. Nạp 1000 test là hàng phút; giữ nó trong một request nghĩa là
 * một connection treo hàng phút, một timeout proxy làm mất toàn bộ công việc, và không có cách
 * nào biết nó đang tới đâu (Quy tắc 5 của {@code frplan.md}).
 *
 * <h2>★ Vì sao phải qua một file tạm trên đĩa</h2>
 * Kho là content-addressed: <b>khoá là hash của nội dung</b>, nên hash phải biết trước khi
 * ghi. Một luồng HTTP chỉ đọc được một lần, nên có ba lựa chọn — giữ 200MB trong RAM, ghi
 * lên kho bằng một khoá tạm rồi đổi tên, hoặc ghi ra đĩa rồi băm.
 *
 * <p>Chọn cái thứ ba: cái đầu làm hai người nạp cùng lúc thành 400MB heap; cái thứ hai để lại
 * rác trên kho mỗi lần hỏng giữa chừng, và MinIO không có "đổi tên" mà chỉ có sao chép — tức
 * là ghi 200MB thêm một lần nữa.
 *
 * <p>File tạm luôn bị xoá trong {@code finally}, kể cả khi hỏng.
 */
@RequiresRole(Role.SETTER)
@Service
public class ImportTestdataUseCase {

    private static final Logger log = LoggerFactory.getLogger(ImportTestdataUseCase.class);

    private final CurrentUserProvider currentUser;
    private final ProblemAuthoringRepository problems;
    private final TestdataStore store;
    private final JobRepository jobs;
    private final ContestWindowQuery lichThi;

    public ImportTestdataUseCase(CurrentUserProvider currentUser,
                                 ProblemAuthoringRepository problems,
                                 TestdataStore store, JobRepository jobs,
                                 ContestWindowQuery lichThi) {
        this.currentUser = currentUser;
        this.problems = problems;
        this.store = store;
        this.jobs = jobs;
        this.lichThi = lichThi;
    }

    /**
     * @param goi    luồng file ZIP. Người gọi đóng
     * @param soByte kích thước gói, để kiểm trần 200MB và tính tỉ lệ nén
     * @return {@code jobs.id} để theo dõi tiến độ qua {@code GET /api/v1/jobs/{id}}
     */
    public long thucHien(long problemId, InputStream goi, long soByte) {
        if (soByte > TestdataLimits.MAX_ZIP_BYTES) {
            throw ProblemsException.khongHopLe("problem.zip_qua_lon",
                    "Gói testdata vượt " + (TestdataLimits.MAX_ZIP_BYTES / 1024 / 1024) + "MB.");
        }
        var nguoiGoi = currentUser.current();
        // Kiểm quyền TRƯỚC khi nhận 200MB: điều kiện chủ sở hữu nằm trong câu query, và một
        // người không sở hữu đề thì không có lý do gì được tải lên một byte nào.
        problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));

        // ★ FR-PROB-11, M5 Bước 5.3 — thay testdata giữa kỳ thi là chuyện tệ hơn hẳn sửa đề
        // bài: các bài đã chấm giữ verdict theo bộ test cũ, bài nộp sau chấm bằng bộ test mới,
        // và bảng xếp hạng so hai thứ không so được với nhau. Chặn TRƯỚC khi nhận file.
        if (lichThi.deDangTrongContestDangChay(problemId)) {
            throw ProblemsException.dangTrongKyThi();
        }

        String sha = luuTamRoiDayLenKho(goi);
        long jobId = jobs.tao(JobType.TESTDATA_IMPORT,
                Map.of("problemId", problemId, "zipSha256", sha, "zipBytes", soByte),
                nguoiGoi.id());
        log.info("Đã nhận gói testdata cho đề {} — job {}", problemId, jobId);
        return jobId;
    }

    private String luuTamRoiDayLenKho(InputStream goi) {
        Path tam = null;
        try {
            tam = Files.createTempFile("oj-testdata-", ".zip");
            String sha = chepVaBam(goi, tam);
            if (!store.daCo(sha)) {
                try (InputStream in = Files.newInputStream(tam)) {
                    store.luu(sha, in, Files.size(tam));
                }
            }
            return sha;
        } catch (IOException e) {
            log.error("Không ghi được file tạm khi nhận gói testdata", e);
            throw ProblemsException.khongHopLe("problem.tai_len_hong",
                    "Không nhận được gói. Thử lại.");
        } finally {
            xoa(tam);
        }
    }

    private static String chepVaBam(InputStream goi, Path dich) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
        try (DigestInputStream in = new DigestInputStream(goi, md);
             OutputStream out = Files.newOutputStream(dich)) {
            in.transferTo(out);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Xoá file tạm. Hỏng ở đây <b>không</b> được ném ra ngoài: gói đã lên kho và job đã được
     * xếp, nên một file tạm còn sót lại là chuyện của đĩa, không phải của người dùng.
     */
    private static void xoa(Path tam) {
        if (tam == null) {
            return;
        }
        try {
            Files.deleteIfExists(tam);
        } catch (IOException e) {
            log.warn("Không xoá được file tạm {}: {}", tam, e.toString());
        }
    }
}
