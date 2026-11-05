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

    public long thucHien(long problemId, InputStream goi, long soByte) {
        if (soByte > TestdataLimits.MAX_ZIP_BYTES) {
            throw ProblemsException.khongHopLe("problem.zip_qua_lon",
                    "Gói testdata vượt " + (TestdataLimits.MAX_ZIP_BYTES / 1024 / 1024) + "MB.");
        }
        var nguoiGoi = currentUser.current();
        problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));

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
