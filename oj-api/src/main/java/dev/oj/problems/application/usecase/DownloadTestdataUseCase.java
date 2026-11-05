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

    public GoiTestdata tai(long problemId, Integer version) {
        var nguoiGoi = currentUser.current();

        problems.findForAuthorById(problemId, nguoiGoi.id(), nguoiGoi.isAdmin())
                .orElseThrow(() -> ProblemNotFoundException.byId(problemId));

        String sha = testdata.shaCuaPhienBan(problemId, version)
                .orElseThrow(() -> ProblemNotFoundException.noTestdata(problemId));

        Map<String, Object> chiTiet = new HashMap<>();
        chiTiet.put("version", version == null ? "hiện hành" : version);
        chiTiet.put("sha256", sha);
        auditLog.ghi("PROBLEM_TESTDATA_DOWNLOADED", "problem", problemId, chiTiet);
        log.info("SETTER {} tải testdata đề {} (phiên bản {})",
                nguoiGoi.id(), problemId, chiTiet.get("version"));

        return new GoiTestdata(sha, store.doc(sha));
    }

    public record GoiTestdata(String sha256, InputStream noiDung) {

        public String tenFile() {
            return "testdata-" + sha256.substring(0, 12) + ".zip";
        }
    }
}
