package dev.oj.contests.application.usecase;

import dev.oj.contests.application.StandingsEventBus;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.error.DomainException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DangKyVaCongBoTest {

    private static final long KY_THI = 5L;
    private static final Instant BAT_DAU = Instant.parse("2026-12-01T09:00:00Z");
    private static final Instant KET_THUC = BAT_DAU.plus(Duration.ofHours(5));
    private static final Duration MOT_GIAY = Duration.ofSeconds(1);

    private final KyThiGia kyThi = new KyThiGia();
    private final List<String> audit = new ArrayList<>();
    private final List<Long> suKien = new ArrayList<>();

    private RegisterForContestUseCase dangKyLuc(Instant luc) {
        return new RegisterForContestUseCase(() -> new CurrentUser(7L, "an", Role.USER), kyThi,
                (h, l, id, ct) -> audit.add(h), Clock.fixed(luc, ZoneOffset.UTC));
    }

    private RevealStandingsUseCase congBoLuc(Instant luc) {
        return new RevealStandingsUseCase(kyThi, new BusGia(), (h, l, id, ct) -> audit.add(h),
                Clock.fixed(luc, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("★ DK-02 · đăng ký lúc starts_at − 1s được; đúng starts_at hay sau giờ kết thúc → 409")
    void dk02_moc_dang_ky() {
        dangKyLuc(BAT_DAU.minus(MOT_GIAY)).thucHien(KY_THI);

        assertThat(kyThi.loiGoi).containsExactly("dangKy 5/7 lúc " + BAT_DAU.minus(MOT_GIAY));
        assertThat(audit).containsExactly("CONTEST_REGISTERED");

        for (Instant muon : List.of(BAT_DAU, KET_THUC.plus(MOT_GIAY))) {
            assertThatThrownBy(() -> dangKyLuc(muon).thucHien(KY_THI))
                    .as("đăng ký lúc %s", muon)
                    .isInstanceOf(ContestsException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("code", "contest.dang_ky_da_dong");
        }
        assertThat(kyThi.loiGoi).as("lượt bị từ chối không được ghi gì").hasSize(1);
        assertThat(audit).hasSize(1);
    }

    @Test
    @DisplayName("DK-04 · đăng ký kỳ thi không tồn tại → 404 contest.khong_tim_thay, không ghi gì")
    void dk04_ky_thi_khong_ton_tai() {
        assertThatThrownBy(() -> dangKyLuc(BAT_DAU.minus(Duration.ofDays(1))).thucHien(KY_THI + 1))
                .isInstanceOf(ContestsException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.NOT_FOUND)
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");

        assertThat(kyThi.loiGoi).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("★ DB-09 · công bố lúc ends_at − 1s → 409 contest.chua_ket_thuc, không đổi gì")
    void db09_cong_bo_truoc_gio() {
        assertThatThrownBy(() -> congBoLuc(KET_THUC.minus(MOT_GIAY)).thucHien(KY_THI))
                .isInstanceOf(ContestsException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                .hasFieldOrPropertyWithValue("code", "contest.chua_ket_thuc");

        assertThat(kyThi.loiGoi).isEmpty();
        assertThat(suKien).isEmpty();
        assertThat(audit).isEmpty();
    }

    @Test
    @DisplayName("★ DB-10 · công bố đúng ends_at thì mở bảng; lần hai không ghi, không báo, không audit")
    void db10_cong_bo_hai_lan() {
        congBoLuc(KET_THUC).thucHien(KY_THI);
        congBoLuc(KET_THUC.plus(Duration.ofMinutes(1))).thucHien(KY_THI);

        assertThat(kyThi.loiGoi).containsExactly("congBo 5 lúc " + KET_THUC);
        assertThat(suKien).containsExactly(KY_THI);
        assertThat(audit).containsExactly("CONTEST_STANDINGS_REVEALED");
    }

    private static final class KyThiGia implements ContestRepository {

        final List<String> loiGoi = new ArrayList<>();
        Contest contest = new Contest(KY_THI, "thu", "Thi thử", ContestFormats.tuMa("ICPC"),
                BAT_DAU, KET_THUC, KET_THUC.minus(Duration.ofHours(1)), null, 20, true, true, 1L);

        @Override
        public Optional<Contest> timTheoId(long contestId) {
            return Optional.of(contest).filter(c -> c.id() == contestId);
        }

        @Override
        public void dangKy(long contestId, long userId, Instant luc) {
            loiGoi.add("dangKy " + contestId + "/" + userId + " lúc " + luc);
        }

        @Override
        public void congBo(long contestId, Instant luc) {
            loiGoi.add("congBo " + contestId + " lúc " + luc);
            contest = new Contest(contest.id(), contest.slug(), contest.title(), contest.format(),
                    contest.startsAt(), contest.endsAt(), contest.freezeAt(), luc,
                    contest.penaltyMinutes(), contest.registrationRequired(),
                    contest.revealAfterEnd(), contest.createdBy());
        }

        @Override public Optional<Contest> timTheoSlug(String slug) { throw chuaCan(); }
        @Override public long tao(ContestMoi moi) { throw chuaCan(); }
        @Override public List<DeCuaContest> deCua(long contestId) { throw chuaCan(); }
        @Override public boolean daDangKy(long contestId, long userId) { throw chuaCan(); }
        @Override public List<Contest> danhSach(Long sauId, int gioiHan) { throw chuaCan(); }
        @Override public List<Contest> canCapNhat(Instant t) { throw chuaCan(); }
        @Override public void chotBangXepHang(long c, Instant l) { throw chuaCan(); }
        @Override public List<Contest> canDongBang(Instant bayGio) { throw chuaCan(); }
    }

    private final class BusGia implements StandingsEventBus {

        @Override
        public void bangDaDoi(long contestId) {
            suKien.add(contestId);
        }

        @Override
        public AutoCloseable subscribe(long contestId, Runnable khiDoi) {
            throw chuaCan();
        }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
