package dev.oj.platform.jobs;

import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.problems.domain.ProblemsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Lỗi nghiệp vụ của một job phải tới được người bấm nút — FR-PROB-03.
 *
 * <h2>Lỗi có thật mà file này canh</h2>
 * {@code JobRunner} bắt riêng {@link JobsException} rồi gom <b>mọi</b> {@code RuntimeException}
 * còn lại vào một câu duy nhất: <i>"Công việc dừng vì một lỗi không lường trước. Xem log với
 * traceId …"</i>. Chú thích ngay cạnh nhánh ấy nói {@code publicMessage()} của
 * {@code DomainException} là an toàn để hiện — nhưng không nhánh nào hiện nó.
 *
 * <p>Hệ quả đo được trên máy chủ thật: {@code ZipTestdataValidator} ném một câu chỉ đúng chỗ
 * sai — <i>"Gói chứa một mục không hợp lệ: '1.in'. Chỉ chấp nhận problem.yaml và
 * tests/&lt;tên&gt;.in|.out."</i> — và người ra đề nhận lại một traceId. Họ không có quyền đọc
 * log. Một thông báo viết sẵn cho họ bị đổi lấy một mã số chỉ người vận hành tra được.
 *
 * <h2>Ba nhánh, và ca thứ ba giữ cho hai ca đầu không đi quá xa</h2>
 * Sửa quá tay là để {@code getMessage()} của một exception bất kỳ lọt ra — bảng {@code jobs}
 * thì ADMIN đọc được, và một stack trace ở đó là bất biến #9 bị chạm.
 */
class JobRunnerLoiNghiepVuTest {

    private static final Instant LUC = Instant.parse("2026-09-04T07:00:00Z");

    private final JobGia jobs = new JobGia();

    private JobRunner runner(JobHandler handler) {
        return new JobRunner(jobs, List.of(handler),
                AppPropertiesGia.macDinh(), Clock.fixed(LUC, ZoneOffset.UTC));
    }

    /** {@code nhip()} đẩy việc sang một executor riêng, nên phải chờ nó chạy xong. */
    private void chayXong(JobRunner runner) throws Exception {
        runner.nhip();
        for (int i = 0; i < 200 && jobs.ketThucCuoi == null; i++) {
            Thread.sleep(10);
        }
        assertThat(jobs.ketThucCuoi).as("job không kết thúc trong 2 giây").isNotNull();
    }

    // =========================================================================

    @Test
    @DisplayName("★ lỗi nghiệp vụ giữ nguyên câu đã viết cho người dùng")
    void loi_nghiep_vu_giu_nguyen_cau_chu() throws Exception {
        chayXong(runner(nem(ProblemsException.khongHopLe("problem.zip_ten_file_la",
                "Gói chứa một mục không hợp lệ: '1.in'. Chỉ chấp nhận problem.yaml và "
                        + "tests/<tên>.in|.out."))));

        assertThat(jobs.ketThucCuoi.status()).isEqualTo(JobStatus.FAILED);
        assertThat(jobs.ketThucCuoi.thongDiep())
                .as("người ra đề phải đọc được chỗ nào sai, không phải một traceId")
                .contains("'1.in'")
                .contains("tests/")
                .doesNotContain("traceId");
    }

    @Test
    @DisplayName("★ và nó cũng vào dòng sự kiện, để dòng thời gian không cụt ở INFO")
    void loi_nghiep_vu_vao_dong_su_kien() throws Exception {
        chayXong(runner(nem(ProblemsException.khongHopLe("problem.zip_thieu_cap",
                "Test '3' thiếu file .out."))));

        assertThat(jobs.suKien)
                .anySatisfy(s -> assertThat(s).contains("ERROR").contains("thiếu file .out"));
    }

    @Test
    @DisplayName("★ lỗi KHÔNG lường trước vẫn phải giấu — bảng jobs thì ADMIN đọc được")
    void loi_that_su_bat_ngo_van_giau() throws Exception {
        chayXong(runner(nem(new NullPointerException("de.ownerId() vì de là null"))));

        assertThat(jobs.ketThucCuoi.thongDiep())
                .as("bất biến #9 — không rò chi tiết nội bộ vào một bảng người khác đọc được")
                .doesNotContain("NullPointerException")
                .doesNotContain("ownerId")
                .contains("traceId");
    }

    @Test
    @DisplayName("JobsException vẫn đi đường cũ của nó")
    void jobs_exception_khong_doi_hanh_vi() throws Exception {
        chayXong(runner(nem(JobsException.daKetThuc())));

        assertThat(jobs.ketThucCuoi.status()).isEqualTo(JobStatus.FAILED);
        assertThat(jobs.ketThucCuoi.thongDiep()).contains("đã kết thúc");
    }

    // =========================================================================

    private static JobHandler nem(RuntimeException loi) {
        return new JobHandler() {
            @Override
            public JobType type() {
                return JobType.TESTDATA_IMPORT;
            }

            @Override
            public void chay(JobContext ctx) {
                throw loi;
            }
        };
    }

    private record KetThuc(JobStatus status, String thongDiep) {
    }

    private static final class JobGia implements JobRepository {
        private final AtomicBoolean daGiao = new AtomicBoolean(false);
        final List<String> suKien = new ArrayList<>();
        volatile KetThuc ketThucCuoi;

        @Override
        public Optional<Job> claim(String leaseOwner, Instant leaseUntil) {
            // Đúng MỘT job: nhịp kế tiếp phải rỗng, nếu không test chạy vô hạn.
            if (daGiao.compareAndSet(false, true)) {
                return Optional.of(new Job(1L, JobType.TESTDATA_IMPORT, JobStatus.RUNNING,
                        Map.of("problemId", 1L), Map.of(), null, 0,
                        leaseOwner, leaseUntil, 1L, LUC, LUC, null, null));
            }
            return Optional.empty();
        }

        @Override
        public void ketThuc(long jobId, JobStatus status, String errorMessage, Instant luc) {
            ketThucCuoi = new KetThuc(status, errorMessage);
        }

        @Override
        public void ghiSuKien(long jobId, String muc, String thongDiep) {
            suKien.add(muc + " · " + thongDiep);
        }

        @Override public int thuHoiJobTreo(Instant bayGio) { return 0; }
        @Override public long tao(JobType t, Map<String, Object> p, Long c) { throw chuaCan(); }
        @Override public Optional<Job> timTheoId(long j) { throw chuaCan(); }
        @Override public Optional<Job> timChoNguoiGoi(long j, long r, boolean a) {
            throw chuaCan();
        }
        @Override public List<Job> ganDay(Long c, Long s, int g) { throw chuaCan(); }
        @Override public boolean nhipTim(long j, String o, int d, Integer t, Instant l) {
            return true;
        }
        @Override public void luuViTri(long j, Map<String, Object> v) { throw chuaCan(); }
        @Override public void tamNghi(long j, String l) { throw chuaCan(); }
        @Override public void huy(long j, Instant l) { throw chuaCan(); }
        @Override public List<JobEvent> suKienGanDay(long j, int g) { throw chuaCan(); }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
