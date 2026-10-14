package dev.oj.problems.application;

import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobParams;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ FR-PROB-10 — ba nghĩa của "đã có job chấm lại". Xem javadoc của {@link NghiaVuChamLai}.
 *
 * <p>Ca quan trọng nhất là {@link #chay_lai_gap_job_cua_chinh_minh_khong_bao_dong()}: trước
 * bản sửa này nó ghi ERROR "phải tạo job chấm lại bằng tay" trong khi job ấy đã có sẵn. Ca đối
 * trọng là {@link #job_cu_tu_truoc_khi_doi_testdata_van_bao_dong()} — sửa ca đầu mà làm mất ca
 * này là đổi một báo động giả lấy một sai lệch im lặng.
 */
class NghiaVuChamLaiTest {

    private static final long DE = 42L;
    private static final long SETTER = 2L;

    private final JobsGia jobs = new JobsGia();
    private final NguCanh ctx = new NguCanh();
    private final NghiaVuChamLai nghiaVu = new NghiaVuChamLai(jobs);

    @Test
    @DisplayName("phiên bản 1 — chưa có bài nộp nào để chấm lại, không tạo job")
    void phien_ban_dau_khong_tao() {
        assertThat(nghiaVu.thucHien(ctx, DE, 1, SETTER)).isNull();
        assertThat(jobs.daTao).isEmpty();
        assertThat(ctx.muc).isEmpty();
    }

    @Test
    @DisplayName("★ tạo job chấm lại mang theo phiên bản testdata nó phủ")
    void tao_moi_mang_phien_ban() {
        Long id = nghiaVu.thucHien(ctx, DE, 3, SETTER);

        assertThat(id).isNotNull();
        assertThat(jobs.daTao).singleElement().satisfies(p -> {
            assertThat(p).containsEntry(JobParams.PROBLEM_ID, DE);
            assertThat(p).containsEntry(JobParams.TESTDATA_VERSION, 3);
        });
        assertThat(ctx.muc).containsExactly("WARN");
    }

    @Test
    @DisplayName("★ chạy lại gặp job chấm lại của chính mình → INFO, không phải ERROR")
    void chay_lai_gap_job_cua_chinh_minh_khong_bao_dong() {
        jobs.dangChay = job(77L, Map.of(JobParams.PROBLEM_ID, DE, JobParams.TESTDATA_VERSION, 3));

        assertThat(nghiaVu.thucHien(ctx, DE, 3, SETTER)).isEqualTo(77L);
        assertThat(ctx.muc).containsExactly("INFO");
        assertThat(ctx.thongDiep.getFirst()).contains("#77");
    }

    @Test
    @DisplayName("★ job ADMIN có từ trước khi đổi testdata → VẪN báo ERROR")
    void job_cu_tu_truoc_khi_doi_testdata_van_bao_dong() {
        jobs.dangChay = job(5L, Map.of(JobParams.PROBLEM_ID, DE));   // không có testdataVersion

        assertThat(nghiaVu.thucHien(ctx, DE, 3, SETTER)).isNull();
        assertThat(ctx.muc).containsExactly("ERROR");
        assertThat(ctx.thongDiep.getFirst()).contains("#5").contains("bằng tay");
    }

    @Test
    @DisplayName("★ job chấm lại của phiên bản CŨ hơn → VẪN báo ERROR")
    void job_cua_phien_ban_cu_van_bao_dong() {
        jobs.dangChay = job(6L, Map.of(JobParams.PROBLEM_ID, DE, JobParams.TESTDATA_VERSION, 2));

        assertThat(nghiaVu.thucHien(ctx, DE, 3, SETTER)).isNull();
        assertThat(ctx.muc).containsExactly("ERROR");
    }

    @Test
    @DisplayName("job kia vừa kết thúc giữa lúc đụng và lúc hỏi → thử lại một lần và tạo được")
    void job_kia_vua_ket_thuc_thi_thu_lai() {
        jobs.dungLanLuot.add(true);   // lần tạo đầu đụng index, lần sau thì không
        // dangChay = null: lúc hỏi thì job kia đã xong

        Long id = nghiaVu.thucHien(ctx, DE, 3, SETTER);

        assertThat(id).isNotNull();
        assertThat(jobs.daTao).hasSize(1);
        assertThat(ctx.muc).containsExactly("WARN");
    }

    @Test
    @DisplayName("lỗi khác (database chết) → ERROR, nhưng KHÔNG ném — testdata đã kích hoạt rồi")
    void loi_khac_khong_lam_hong_viec_nap() {
        jobs.nemLoiKhac = true;

        assertThat(nghiaVu.thucHien(ctx, DE, 3, SETTER)).isNull();
        assertThat(ctx.muc).containsExactly("ERROR");
    }

    // =========================================================================

    private static Job job(long id, Map<String, Object> params) {
        return new Job(id, JobType.REJUDGE, JobStatus.RUNNING, params, Map.of(), null, 0,
                "api-1", null, null, Instant.EPOCH, null, null, null);
    }

    /** Ghi lại mức và nội dung từng sự kiện — thứ người vận hành đọc trên trang job. */
    private static final class NguCanh implements JobContext {
        final List<String> muc = new ArrayList<>();
        final List<String> thongDiep = new ArrayList<>();

        @Override public long jobId() { return 1L; }
        @Override public Map<String, Object> params() { return Map.of(); }
        @Override public Long nguoiTao() { return SETTER; }
        @Override public Map<String, Object> viTriDaLuu() { return new HashMap<>(); }
        @Override public void tienDo(int daXong, Integer tong) { }
        @Override public void luuViTri(Map<String, Object> viTri) { }
        @Override public void kiemHuy() { }

        @Override
        public void ghiSuKien(String m, String t) {
            muc.add(m);
            thongDiep.add(t);
        }
    }

    /**
     * {@code tao} đụng index khi có {@link #dangChay}, hoặc theo hàng đợi {@link #dungLanLuot}
     * để giả lập job kia kết thúc giữa chừng.
     */
    private static final class JobsGia implements JobRepository {
        final List<Map<String, Object>> daTao = new ArrayList<>();
        final Deque<Boolean> dungLanLuot = new ArrayDeque<>();
        Job dangChay;
        boolean nemLoiKhac;

        @Override
        public long tao(JobType type, Map<String, Object> params, Long createdBy) {
            if (nemLoiKhac) {
                throw new IllegalStateException("database chết");
            }
            boolean dung = dungLanLuot.isEmpty() ? dangChay != null : dungLanLuot.poll();
            if (dung) {
                throw JobsException.dangCoJobCungLoai(type);
            }
            daTao.add(params);
            return 100L + daTao.size();
        }

        @Override
        public Optional<Job> dangSong(JobType type, String khoaThucThe) {
            return Optional.ofNullable(dangChay);
        }

        @Override public Optional<Job> timTheoId(long j) { throw chuaCan(); }
        @Override public Optional<Job> timChoNguoiGoi(long j, long r, boolean a) {
            throw chuaCan();
        }
        @Override public List<Job> ganDay(Long c, Long s, int g) { throw chuaCan(); }
        @Override public Optional<Job> claim(String o, Instant u) { throw chuaCan(); }
        @Override public boolean nhipTim(long j, String o, int d, Integer t, Instant l) {
            throw chuaCan();
        }
        @Override public void luuViTri(long j, Map<String, Object> v) { throw chuaCan(); }
        @Override public int thuHoiJobTreo(Instant b) { throw chuaCan(); }
        @Override public void ketThuc(long j, JobStatus s, String e, Instant l) {
            throw chuaCan();
        }
        @Override public void tamNghi(long j, String l) { throw chuaCan(); }
        @Override public void huy(long j, Instant l) { throw chuaCan(); }
        @Override public void ghiSuKien(long j, String m, String t) { throw chuaCan(); }
        @Override public List<JobEvent> suKienGanDay(long j, int g) { throw chuaCan(); }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
