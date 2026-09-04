package dev.oj.contests.application;

import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestFormat.TongKet;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.jobs.Job;

import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Lưới an toàn của bảng xếp hạng phải có người kéo — FR-CON-09.
 *
 * <h2>Lỗ hổng mà file này canh</h2>
 * {@code StandingsDriftCheckJob} có đủ mọi thứ trừ một chỗ gọi. Trước lớp
 * {@link StandingsDriftCheckScheduler}, toàn bộ mã nguồn chỉ có ba chỗ tạo job và không chỗ
 * nào tạo loại {@code STANDINGS_DRIFT_CHECK} — nên bảng {@code standings_drift_checks} rỗng
 * suốt vòng đời hệ thống và mọi lệch đều vô hình.
 *
 * <p>Đó là loại lỗi không test nào bắt được bằng cách chạy thứ đang có: mọi thành phần đều
 * đúng, chỉ có sợi dây nối là không tồn tại.
 */
class StandingsDriftCheckSchedulerTest {

    private static final Instant BAY_GIO = Instant.parse("2026-09-04T10:00:00Z");

    private final StandingsGia standings = new StandingsGia();
    private final JobGia jobs = new JobGia();
    private final StandingsDriftCheckScheduler nhip = new StandingsDriftCheckScheduler(
            standings, jobs, AppPropertiesGia.macDinh(),
            Clock.fixed(BAY_GIO, ZoneOffset.UTC));

    @Test
    @DisplayName("★ mỗi kỳ thi cần soát sinh đúng một việc STANDINGS_DRIFT_CHECK")
    void tao_viec_cho_tung_ky_thi() {
        standings.canSoat = List.of(7L, 9L);

        nhip.nhip();

        assertThat(jobs.daTao).hasSize(2);
        assertThat(jobs.daTao).allSatisfy(v ->
                assertThat(v.type()).isEqualTo(JobType.STANDINGS_DRIFT_CHECK));
        assertThat(jobs.daTao).extracting(v -> v.params().get("contestId"))
                .containsExactly(7L, 9L);
    }

    @Test
    @DisplayName("★ hai mốc thời gian truyền xuống đúng cửa sổ và nhịp trong cấu hình")
    void truyen_dung_hai_moc() {
        nhip.nhip();

        var c = AppPropertiesGia.macDinh().contest();
        assertThat(standings.bayGioNhan).isEqualTo(BAY_GIO);
        assertThat(standings.ketThucSauNhan)
                .as("cửa sổ — kỳ thi cũ hơn thế coi như đã chốt")
                .isEqualTo(BAY_GIO.minus(c.driftCheckWindow()));
        assertThat(standings.chuaSoatTuNhan)
                .as("chốt chống soát lại quá dày; mỗi lần soát là một lần quét cả kỳ thi")
                .isEqualTo(BAY_GIO.minus(c.driftCheckInterval()));
    }

    @Test
    @DisplayName("★ việc soát đang chạy thì bỏ qua, KHÔNG làm hỏng nhịp cho kỳ thi sau")
    void job_dang_chay_khong_chan_ky_thi_khac() {
        standings.canSoat = List.of(1L, 2L, 3L);
        jobs.nemChoContest = 2L;   // ux_jobs_one_active_per_entity của V9

        nhip.nhip();

        assertThat(jobs.daTao).extracting(v -> v.params().get("contestId"))
                .as("kỳ thi 3 vẫn phải được tạo việc dù kỳ thi 2 vừa ném")
                .containsExactly(1L, 3L);
    }

    @Test
    @DisplayName("★ repository ném thì NUỐT — Spring huỷ hẳn tác vụ @Scheduled nếu nó ném ra")
    void loi_khong_duoc_thoat_ra_ngoai() {
        standings.nem = true;

        nhip.nhip();   // không được ném

        assertThat(jobs.daTao).isEmpty();
    }

    @Test
    @DisplayName("không có kỳ thi nào thì không tạo việc nào")
    void khong_co_gi_thi_khong_lam_gi() {
        standings.canSoat = List.of();

        nhip.nhip();

        assertThat(jobs.daTao).isEmpty();
    }

    // =========================================================================

    private record ViecDaTao(JobType type, Map<String, Object> params) {
    }

    private static final class StandingsGia implements StandingsRepository {
        List<Long> canSoat = List.of();
        boolean nem;
        Instant bayGioNhan;
        Instant ketThucSauNhan;
        Instant chuaSoatTuNhan;

        @Override
        public List<Long> canSoatLech(Instant bayGio, Instant ketThucSau, Instant chuaSoatTu) {
            if (nem) {
                throw new IllegalStateException("database dỗi");
            }
            bayGioNhan = bayGio;
            ketThucSauNhan = ketThucSau;
            chuaSoatTuNhan = chuaSoatTu;
            return canSoat;
        }

        @Override public long watermark(long c) { throw chuaCan(); }
        @Override public Map<Long, List<KetQuaDe>> ketQuaTheoNguoi(long c, Collection<Long> u) {
            throw chuaCan();
        }
        @Override public void ghiKetQuaDe(long c, long u, KetQuaDe k) { throw chuaCan(); }
        @Override public void ghiTongKet(long c, long u, TongKet t, int p, long b) {
            throw chuaCan();
        }
        @Override public void xoaBangXepHang(long c) { throw chuaCan(); }
        @Override public boolean daChupDongBang(long c) { throw chuaCan(); }
        @Override public void chupDongBang(long c, Instant f) { throw chuaCan(); }
        @Override public Map<Long, DiemDaLuu> tongKetDaLuu(long c) { throw chuaCan(); }
        @Override public void ghiDrift(long c, int k, int l, Map<String, Object> ct) {
            throw chuaCan();
        }
    }

    private static final class JobGia implements JobRepository {
        final List<ViecDaTao> daTao = new ArrayList<>();
        Long nemChoContest;

        @Override
        public long tao(JobType type, Map<String, Object> params, Long createdBy) {
            if (nemChoContest != null && nemChoContest.equals(params.get("contestId"))) {
                throw JobsException.dangCoJobCungLoai(type);
            }
            daTao.add(new ViecDaTao(type, params));
            return daTao.size();
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
        @Override public List<JobRepository.JobEvent> suKienGanDay(long j, int g) { throw chuaCan(); }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
