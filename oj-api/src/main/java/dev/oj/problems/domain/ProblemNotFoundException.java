package dev.oj.problems.domain;

import dev.oj.platform.error.DomainException;

public class ProblemNotFoundException extends DomainException {

    private ProblemNotFoundException(String logMessage) {
        super(Kind.NOT_FOUND,
                "problem.not_found",
                "Không tìm thấy đề bài này.",
                logMessage);
    }

    public static ProblemNotFoundException byCode(String code) {
        return new ProblemNotFoundException("Không có đề PUBLISHED với mã: " + code);
    }

    public static ProblemNotFoundException byId(long id) {
        return new ProblemNotFoundException("Không có đề PUBLISHED với id: " + id);
    }

    public static ProblemNotFoundException noTestdata(long id) {
        return new ProblemNotFoundException(
                "Đề id=" + id + " đã PUBLISHED nhưng current_testdata_version = 0 — "
                        + "SETTER xuất bản mà quên upload testdata");
    }
}
