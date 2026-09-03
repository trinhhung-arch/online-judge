package dev.oj.judging.application.usecase;

import dev.oj.judging.application.RejudgeJobHandler;
import dev.oj.judging.domain.JudgingException;
import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;
import dev.oj.platform.settings.SystemSettings;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Hai use-case quản trị của {@code judging}: FR-ADM-01 (Bước 6.3) và FR-SUB-09 (Bước 6.13).
 *
 * <p>Fake repository + fake queue, đúng bảng mục 6 của {@code CLAUDE.md}. Không context, không
 * database — nên mỗi ca ở đây kiểm đúng một quyết định nghiệp vụ và không kiểm gì khác.
 */
class AdminJudgingUseCasesTest {

    private static final long ADMIN_ID = 9L;
    private static final long DE = 42L;

    private JudgingFakes fakes;
    private LichThiGia lichThi;
    private JudgingFakes.CongTacGia congTac;
    private JobsGia jobs;
    private IdentityFreeAuditLog nhatKy;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        lichThi = new LichThiGia();
        congTac = new JudgingFakes.CongTacGia();
        jobs = new JobsGia();
        nhatKy = new IdentityFreeAuditLog();
    }

    @Nested
    @DisplayName("★ FR-ADM-01 — bắt đầu chấm lại hàng loạt")
    class BatDauChamLai {

        @Test
        @DisplayName("tạo job REJUDGE mang problemId, và ghi audit_log")
        void tao_job_va_ghi_audit() {
            long jobId = useCase().batDau(DE);

            assertThat(jobId).isEqualTo(1L);
            assertThat(jobs.daTao).containsExactly(JobType.REJUDGE);
            assertThat(jobs.thamSo.get(0))
                    .containsEntry(RejudgeJobHandler.THAM_SO_DE, DE);
            assertThat(nhatKy.hanhDong).containsExactly("REJUDGE_STARTED");
        }

        /**
         * ★ Chốt quan trọng nhất của FR-ADM-01.
         *
         * <p>{@code frplan.md} mâu thuẫn 3.2: một đề phổ biến có 10.000 bài nộp, và ở
         * throughput 5 bài/s thì hàng đợi tắc 33 phút. Trần 30% năng lực <b>không cứu được</b>
         * điều này giữa contest, vì thứ bị phá không phải throughput mà là tính công bằng —
         * bài nộp phút thứ 90 chấm chậm hơn bài phút thứ 5.
         */
        @Test
        @DisplayName("★ có kỳ thi đang chạy thì từ chối, và KHÔNG tạo job nào")
        void co_ky_thi_thi_tu_choi() {
            lichThi.coKyThi = true;

            assertThatExceptionOfType(JudgingException.class)
                    .isThrownBy(() -> useCase().batDau(DE))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("rejudge.contest_dang_chay"));

            assertThat(jobs.daTao).isEmpty();
            assertThat(nhatKy.hanhDong).isEmpty();
        }

        @Test
        @DisplayName("công tắc rejudge.enabled tắt thì từ chối")
        void cong_tac_tat_thi_tu_choi() {
            congTac.dat(SystemSettings.REJUDGE, false, ADMIN_ID);

            assertThatExceptionOfType(JudgingException.class)
                    .isThrownBy(() -> useCase().batDau(DE))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("rejudge.da_tat"));

            assertThat(jobs.daTao).isEmpty();
        }

        /**
         * Không có chốt này thì {@code POST /admin/problems/999999/rejudge} tạo một job chạy
         * xong ngay với 0 bài, và ADMIN tưởng mình vừa chấm lại một đề.
         */
        @Test
        @DisplayName("đề không tồn tại thì 404 và không tạo job")
        void de_khong_ton_tai() {
            assertThatExceptionOfType(dev.oj.problems.domain.ProblemNotFoundException.class)
                    .isThrownBy(() -> useCaseKhongCoDe().batDau(DE));

            assertThat(jobs.daTao).isEmpty();
        }

        @Test
        @DisplayName("đã có job REJUDGE cho cùng đề thì 409 — ux_jobs_one_active_per_entity")
        void double_click_bi_chan() {
            jobs.noTrungLap = true;

            assertThatExceptionOfType(JobsException.class)
                    .isThrownBy(() -> useCase().batDau(DE))
                    .satisfies(e -> assertThat(e.code()).isEqualTo("job.dang_chay"));

            // Audit ghi SAU khi job đã tạo — ghi trước là ghi một việc chưa xảy ra.
            assertThat(nhatKy.hanhDong).isEmpty();
        }
    }

    @Nested
    @DisplayName("FR-SUB-09 — ẩn bài nộp, không xoá")
    class AnBaiNop {

        @Test
        @DisplayName("ẩn được và ghi audit_log")
        void an_duoc() {
            anUseCase().dat(11L, true);

            assertThat(nhatKy.hanhDong).containsExactly("SUBMISSION_HIDDEN");
        }

        @Test
        @DisplayName("★ ẩn một bài đã ẩn thì 404, không im lặng ghi đè hidden_by")
        void an_hai_lan_thi_tu_choi() {
            HideSubmissionUseCase uc = anUseCase();
            uc.dat(11L, true);

            assertThatExceptionOfType(JudgingException.class)
                    .isThrownBy(() -> uc.dat(11L, true));

            // Một dòng audit, không phải hai. Hai dòng nghĩa là nhật ký kiểm toán nói hai
            // người cùng ẩn một bài, trong khi người thứ hai chỉ bấm nhầm.
            assertThat(nhatKy.hanhDong).containsExactly("SUBMISSION_HIDDEN");
        }

        @Test
        @DisplayName("hiện lại được, và ghi một hành động KHÁC")
        void hien_lai_duoc() {
            HideSubmissionUseCase uc = anUseCase();
            uc.dat(11L, true);
            uc.dat(11L, false);

            assertThat(nhatKy.hanhDong)
                    .containsExactly("SUBMISSION_HIDDEN", "SUBMISSION_UNHIDDEN");
        }
    }

    // -------------------------------------------------------------------------

    private StartRejudgeUseCase useCase() {
        return new StartRejudgeUseCase(nguoiGoi(), deCoThat(), lichThi, congTac, jobs, nhatKy);
    }

    private StartRejudgeUseCase useCaseKhongCoDe() {
        return new StartRejudgeUseCase(nguoiGoi(), deKhongCo(), lichThi, congTac, jobs, nhatKy);
    }

    private HideSubmissionUseCase anUseCase() {
        return new HideSubmissionUseCase(nguoiGoi(), fakes.submissions, nhatKy,
                Clock.fixed(Instant.parse("2026-08-30T10:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private CurrentUserProvider nguoiGoi() {
        return () -> new CurrentUserProvider.CurrentUser(ADMIN_ID, "admin", Role.ADMIN);
    }

    private GetProblemUseCase deCoThat() {
        return SubmitSolutionUseCaseTest.getProblemUseCaseTraVe(DE, lichThi);
    }

    private GetProblemUseCase deKhongCo() {
        return SubmitSolutionUseCaseTest.getProblemUseCaseTraVe(null, lichThi);
    }

    /** {@link JobRepository} giả — chỉ ba phương thức use-case này chạm tới. */
    static final class JobsGia implements JobRepository {

        final List<JobType> daTao = new ArrayList<>();
        final List<Map<String, Object>> thamSo = new ArrayList<>();
        boolean noTrungLap;
        private long seq;

        @Override
        public long tao(JobType type, Map<String, Object> params, Long createdBy) {
            if (noTrungLap) {
                throw JobsException.dangCoJobCungLoai(type);
            }
            daTao.add(type);
            thamSo.add(new LinkedHashMap<>(params));
            return ++seq;
        }

        @Override
        public Optional<Job> timTheoId(long jobId) {
            return Optional.empty();
        }

        @Override
        public Optional<Job> timChoNguoiGoi(long jobId, long requesterId, boolean laAdmin) {
            return Optional.empty();
        }

        @Override
        public List<Job> ganDay(Long createdBy, int gioiHan) {
            return List.of();
        }

        @Override
        public Optional<Job> claim(String leaseOwner, Instant leaseUntil) {
            return Optional.empty();
        }

        @Override
        public boolean nhipTim(long jobId, String leaseOwner, int daXong, Integer tong,
                               Instant leaseMoi) {
            return true;
        }

        @Override
        public void luuViTri(long jobId, Map<String, Object> viTri) {
        }

        @Override
        public int thuHoiJobTreo(Instant bayGio) {
            return 0;
        }

        @Override
        public void ketThuc(long jobId, JobStatus status, String errorMessage, Instant luc) {
        }

        @Override
        public void tamNghi(long jobId, String lyDo) {
        }

        @Override
        public void huy(long jobId, Instant luc) {
        }

        @Override
        public void ghiSuKien(long jobId, String muc, String thongDiep) {
        }

        @Override
        public List<JobEvent> suKienGanDay(long jobId, int gioiHan) {
            return List.of();
        }
    }

    /** {@link AuditLog} giả. Bản của {@code IdentityFakes} là package-private ở module khác. */
    static final class IdentityFreeAuditLog implements AuditLog {

        final List<String> hanhDong = new ArrayList<>();

        @Override
        public void ghi(String h, String loai, Long id, Map<String, Object> chiTiet) {
            hanhDong.add(h);
        }
    }
}
