package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.jobs.Job;
import dev.oj.platform.jobs.JobRepository;
import dev.oj.platform.jobs.JobStatus;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartStandingsRebuildUseCaseTest {

    private static final long KY_THI = 5L;
    private static final long ADMIN = 3L;
    private static final Instant BAT_DAU = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant KET_THUC = BAT_DAU.plus(Duration.ofHours(5));

    private final JobGia jobs = new JobGia();
    private final List<String> audit = new ArrayList<>();

    private StartStandingsRebuildUseCase luc(Instant bayGio) {
        return new StartStandingsRebuildUseCase(() -> new CurrentUser(ADMIN, "admin", Role.ADMIN),
                new KyThiGia(), jobs, (h, l, id, ct) -> audit.add(h + " " + id + " " + ct),
                Clock.fixed(bayGio, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("★ DS-05 · kỳ thi đã kết thúc: tạo đúng một job LEADERBOARD_REBUILD cho kỳ thi, ghi người chạy và audit")
    void tao_job_dung_lai() {
        long jobId = luc(KET_THUC).batDau(KY_THI);

        assertThat(jobs.daTao).containsExactly("LEADERBOARD_REBUILD {contestId=5} bởi 3");
        assertThat(audit).containsExactly("STANDINGS_REBUILD_STARTED 5 {jobId=" + jobId + "}");
    }

    @Test
    @DisplayName("★ DS-05 · kỳ thi chưa mở, đang chạy hay còn 1 giây → 409 contest.chua_ket_thuc, không tạo job")
    void chua_ket_thuc_thi_khong_dung_lai() {
        for (Instant som : List.of(BAT_DAU.minusSeconds(1), BAT_DAU, KET_THUC.minusSeconds(1))) {
            assertThatThrownBy(() -> luc(som).batDau(KY_THI))
                    .as("lúc %s", som)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("code", "contest.chua_ket_thuc");
        }

        assertThat(jobs.daTao).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("kỳ thi không tồn tại → 404 contest.khong_tim_thay, không tạo job")
    void ky_thi_khong_ton_tai() {
        assertThatThrownBy(() -> luc(KET_THUC).batDau(KY_THI + 1))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.NOT_FOUND)
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");

        assertThat(jobs.daTao).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("đang có job dựng lại còn sống cho kỳ thi → 409 job.dang_chay, không audit")
    void dang_co_job_thi_khong_audit() {
        jobs.trungLap = true;

        assertThatThrownBy(() -> luc(KET_THUC).batDau(KY_THI))
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                .hasFieldOrPropertyWithValue("code", "job.dang_chay");
        assertThat(audit).isEmpty();
    }

    private static final class KyThiGia implements ContestRepository {

        private final Contest contest = new Contest(KY_THI, "thu", "Thi thử",
                ContestFormats.tuMa("ICPC"), BAT_DAU, KET_THUC, null, null, 20, true, true, 1L);

        @Override
        public Optional<Contest> timTheoId(long contestId) {
            return Optional.of(contest).filter(c -> c.id() == contestId);
        }

        @Override public Optional<Contest> timTheoSlug(String slug) { throw chuaCan(); }
        @Override public long tao(ContestMoi moi) { throw chuaCan(); }
        @Override public List<DeCuaContest> deCua(long contestId) { throw chuaCan(); }
        @Override public void dangKy(long c, long u, Instant l) { throw chuaCan(); }
        @Override public boolean daDangKy(long c, long u) { throw chuaCan(); }
        @Override public List<Contest> danhSach(Long sauId, int gioiHan) { throw chuaCan(); }
        @Override public List<Contest> canCapNhat(Instant t) { throw chuaCan(); }
        @Override public void chotBangXepHang(long c, Instant l) { throw chuaCan(); }
        @Override public List<Contest> canDongBang(Instant bayGio) { throw chuaCan(); }
        @Override public void congBo(long c, Instant l) { throw chuaCan(); }
    }

    private static final class JobGia implements JobRepository {

        final List<String> daTao = new ArrayList<>();
        boolean trungLap;

        @Override
        public long tao(JobType type, Map<String, Object> params, Long createdBy) {
            if (trungLap) {
                throw JobsException.dangCoJobCungLoai(type);
            }
            daTao.add(type + " " + params + " bởi " + createdBy);
            return daTao.size();
        }

        @Override public Optional<Job> timTheoId(long j) { throw chuaCan(); }
        @Override public Optional<Job> timChoNguoiGoi(long j, long r, boolean a) {
            throw chuaCan();
        }
        @Override public List<Job> ganDay(Long c, Long s, int g) { throw chuaCan(); }
        @Override public Optional<Job> dangSong(JobType t, String k) { throw chuaCan(); }
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
