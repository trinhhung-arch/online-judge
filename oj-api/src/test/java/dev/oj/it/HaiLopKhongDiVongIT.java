package dev.oj.it;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.judging.application.usecase.GetSubmissionUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.jobs.application.usecase.CancelJobUseCase;
import dev.oj.platform.jobs.application.usecase.GetJobUseCase;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.application.usecase.ImportTestdataUseCase;
import dev.oj.problems.domain.FeedbackLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ ADMIN chưa bật 2FA KHÔNG đi vòng được cổng hai lớp qua {@code isAdmin()} — phần 1:
 * đề, testdata, bài nộp, job.
 *
 * <h2>Lỗ đã có thật</h2>
 * Cổng từng chỉ được hỏi khi use-case khai {@code @RequiresRole(ADMIN)}. Các use-case dưới đây
 * khai SETTER hoặc USER rồi trao quyền ADMIN bằng {@code isAdmin()} / {@code role()} — nên
 * ADMIN chưa bật 2FA tải được testdata mọi đề, đọc được source mọi người. Chốt giờ nằm ở
 * {@code JwtCurrentUserProvider}; lớp này ghim từng chỗ để gỡ chốt ấy là CI đỏ.
 *
 * <h2>Mỗi ca đi theo cùng một khuôn, và thứ tự là cố ý</h2>
 * <ol>
 *   <li><b>Chưa bật 2FA → bị chặn, và không chạm dữ liệu.</b></li>
 *   <li><b>Bật lại → cùng lời gọi ấy QUA.</b> Nửa này không phải trang trí: thiếu nó thì nửa
 *       đầu xanh cả khi lời gọi hỏng vì một lý do chẳng liên quan gì tới 2FA — đúng kiểu dấu
 *       xanh không chứng minh điều nó có vẻ chứng minh ({@code bao-mat-plan.md} nguyên tắc 1).</li>
 * </ol>
 * Chặn trước, qua sau, để thao tác phá huỷ (xoá đề) chỉ thật sự chạy một lần.
 *
 * <p>Phần 2 — lịch thi và bảng xếp hạng — ở {@link HaiLopKhongDiVongKyThiIT}.
 */
class HaiLopKhongDiVongIT extends HttpIT {

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired GetSubmissionUseCase getSubmission;
    @Autowired AuthorProblemUseCase authorProblem;
    @Autowired ImportTestdataUseCase importTestdata;
    @Autowired GetJobUseCase getJob;
    @Autowired CancelJobUseCase cancelJob;
    @Autowired KhoTestdataTrongBoNho kho;

    @Test
    @DisplayName("★★ GET /problems/{id}/testdata qua HTTP thật — đề của SETTER khác")
    void tai_testdata_de_nguoi_khac() {
        byte[] noiDung = "testdata-an-cua-setter".getBytes(StandardCharsets.UTF_8);
        kho.dat(jdbc.sql("SELECT manifest_sha256 FROM testdata_versions "
                        + "WHERE problem_id = :id AND version = 1")
                .param("id", PROBLEM_ID).query(String.class).single(), noiDung);

        AdminHaiLop.go(jdbc);
        var biChan = taiTestdata();
        assertThat(biChan.getKey().value())
                .as("404 như SETTER của đề khác — không phải 200, cũng không phải 403")
                .isEqualTo(404);
        assertThat(biChan.getValue()).doesNotContain("testdata-an-cua-setter");
        assertThat(soLuotTaiTrongAudit()).isZero();

        AdminHaiLop.bat(jdbc);
        var qua = taiTestdata();
        assertThat(qua.getKey().value()).isEqualTo(200);
        assertThat(qua.getValue()).isEqualTo("testdata-an-cua-setter");
    }

    @Test
    @DisplayName("★ đọc bài nộp của người khác — :requesterRole = 'ADMIN' trong SQL")
    void doc_bai_nop_nguoi_khac() {
        // Nộp TRƯỚC, bằng danh tính USER của PostgresIT — AdminHaiLop.lay xoá danh tính khi đóng.
        long cuaDev = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> getSubmission.detailById(cuaDev)))
                .hasFieldOrPropertyWithValue("code", "submission.not_found");
        // byId là cửa của luồng SSE (WatchSubmissionUseCase) — một câu SQL khác, cùng tham số.
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> getSubmission.byId(cuaDev)))
                .hasFieldOrPropertyWithValue("code", "submission.not_found");

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> getSubmission.detailById(cuaDev)).submission().id())
                .isEqualTo(cuaDev);
        assertThat(AdminHaiLop.lay(() -> getSubmission.byId(cuaDev)).id()).isEqualTo(cuaDev);
    }

    @Test
    @DisplayName("sửa đề của người khác — và bị chặn thì đề KHÔNG đổi")
    void sua_de_nguoi_khac() {
        // Giữ nguyên nội dung đề: chỉ tiêu đề đổi, để không để lại dấu cho IT chạy sau.
        String deBai = jdbc.sql("SELECT statement_md FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(String.class).single();
        var lenh = new AuthorProblemUseCase.Command("A-PLUS-B", "Tiêu đề do admin sửa",
                deBai, 1000, 262144, CheckerType.TOKEN, null,
                ScoringMode.ALL_OR_NOTHING, FeedbackLevel.TEST_INDEX, false);
        // Đọc tại chỗ, không so với "A + B" của seed: ResetGiuaCacTest không hoàn nguyên
        // `title`, và ContestAccessIT chạy trước (thứ tự alphabetical) đã đổi nó. Đo 2026-09-23.
        String truoc = tieuDeDe(PROBLEM_ID);

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> authorProblem.sua(PROBLEM_ID, lenh)))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");
        assertThat(tieuDeDe(PROBLEM_ID)).isEqualTo(truoc);

        AdminHaiLop.bat(jdbc);
        AdminHaiLop.lam(() -> authorProblem.sua(PROBLEM_ID, lenh));
        assertThat(tieuDeDe(PROBLEM_ID)).isEqualTo("Tiêu đề do admin sửa");
    }

    @Test
    @DisplayName("mở đề của người khác để sửa — findForAuthorById")
    void mo_de_nguoi_khac_de_sua() {
        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> authorProblem.doc(PROBLEM_ID)))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> authorProblem.doc(PROBLEM_ID)).code())
                .isEqualTo("A-PLUS-B");
    }

    @Test
    @DisplayName("gỡ xuống đề của người khác — và bị chặn thì đề KHÔNG đổi trạng thái")
    void go_xuong_de_nguoi_khac() {
        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> authorProblem.goXuong(PROBLEM_ID)))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");
        assertThat(trangThaiDe(PROBLEM_ID)).isEqualTo("PUBLISHED");

        AdminHaiLop.bat(jdbc);
        AdminHaiLop.lam(() -> authorProblem.goXuong(PROBLEM_ID));
        assertThat(trangThaiDe(PROBLEM_ID)).isEqualTo("RETIRED");
    }

    @Test
    @DisplayName("xoá đề của người khác — và bị chặn thì đề còn nguyên")
    void xoa_de_nguoi_khac() {
        long id = taoDeNhapCuaSetter();

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> authorProblem.xoa(id)))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");
        assertThat(conDe(id)).isTrue();

        AdminHaiLop.bat(jdbc);
        AdminHaiLop.lam(() -> authorProblem.xoa(id));
        assertThat(conDe(id)).isFalse();
    }

    /**
     * Nửa "qua" dùng một mẹo để không phải dựng một gói ZIP và một job nền: đặt đề vào một kỳ
     * thi ĐANG CHẠY. Qua được chốt chủ sở hữu thì lời gọi dừng ở chốt kế tiếp —
     * {@code problem.dang_trong_ky_thi} — và chính mã lỗi ấy là bằng chứng đã qua.
     */
    @Test
    @DisplayName("nạp testdata cho đề của người khác")
    void nap_testdata_de_nguoi_khac() {
        datVaoKyThiDangChay(PROBLEM_ID);

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> importTestdata.thucHien(
                PROBLEM_ID, new ByteArrayInputStream(new byte[0]), 0)))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");

        AdminHaiLop.bat(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> importTestdata.thucHien(
                PROBLEM_ID, new ByteArrayInputStream(new byte[0]), 0)))
                .hasFieldOrPropertyWithValue("code", "problem.dang_trong_ky_thi");
    }

    @Test
    @DisplayName("job của người khác — xem, liệt kê, huỷ")
    void job_nguoi_khac() {
        long jobId = jdbc.sql("""
                INSERT INTO jobs (type, status, created_by, finished_at)
                VALUES ('REJUDGE', 'DONE', :setter, now()) RETURNING id
                """).param("setter", SETTER_ID).query(Long.class).single();

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> getJob.thucHien(jobId)))
                .hasFieldOrPropertyWithValue("code", "job.khong_tim_thay");
        assertThat(AdminHaiLop.lay(() -> getJob.cuaToi(null, 20)).items())
                .noneMatch(j -> j.id() == jobId);
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> cancelJob.thucHien(jobId)))
                .hasFieldOrPropertyWithValue("code", "job.khong_tim_thay");

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> getJob.thucHien(jobId)).job().id()).isEqualTo(jobId);
        assertThat(AdminHaiLop.lay(() -> getJob.cuaToi(null, 20)).items())
                .anyMatch(j -> j.id() == jobId);
        // Qua chốt chủ sở hữu thì dừng ở chốt trạng thái: job đã DONE không huỷ được nữa.
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> cancelJob.thucHien(jobId)))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "job.da_ket_thuc");
    }

    // ---- sân -----------------------------------------------------------------------------

    private SimpleEntry<HttpStatusCode, String> taiTestdata() {
        return http.get().uri("/api/v1/problems/{id}/testdata", PROBLEM_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(ADMIN_ID, "admin", Role.ADMIN))
                .exchange((req, res) -> new SimpleEntry<>(res.getStatusCode(),
                        new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private int soLuotTaiTrongAudit() {
        return jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'PROBLEM_TESTDATA_DOWNLOADED'")
                .query(Integer.class).single();
    }

    private String trangThaiDe(long id) {
        return jdbc.sql("SELECT status FROM problems WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private String tieuDeDe(long id) {
        return jdbc.sql("SELECT title FROM problems WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    private boolean conDe(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM problems WHERE id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    private long taoDeNhapCuaSetter() {
        return jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, owner_id, status)
                VALUES ('DE-NHAP-CUA-SETTER', 'Đề nháp', 'x', :hash, 1000, 262144, :owner,
                        'DRAFT')
                RETURNING id
                """).param("hash", "c".repeat(64)).param("owner", SETTER_ID)
                .query(Long.class).single();
    }

    private void datVaoKyThiDangChay(long problemId) {
        long contestId = jdbc.sql("""
                INSERT INTO contests (slug, title, format, starts_at, ends_at, created_by)
                VALUES ('dang-chay', 'Đang chạy', 'ICPC', now() - interval '1 hour',
                        now() + interval '1 hour', :setter)
                RETURNING id
                """).param("setter", SETTER_ID).query(Long.class).single();
        jdbc.sql("INSERT INTO contest_problems (contest_id, problem_id, label) "
                        + "VALUES (:c, :p, 'A')")
                .params(Map.of("c", contestId, "p", problemId)).update();
    }
}
