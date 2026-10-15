package dev.oj.contests.application;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsLock;
import dev.oj.contests.application.port.StandingsRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestFormat.TongKet;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.judging.application.published.JudgingQueries;
import dev.oj.platform.config.AppPropertiesGia;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * ★ Một kỳ thi hỏng chỉ được làm hỏng <b>chính nó</b> — hai nhịp {@code @Scheduled} của bảng
 * xếp hạng.
 *
 * <p>Bản trước của cả hai bọc try/catch quanh CẢ vòng lặp. Kỳ thi có id nhỏ ném lỗi ở mọi nhịp
 * thì mọi kỳ thi xếp sau nó không bao giờ tới lượt: bảng xếp hạng đứng im, và với đóng băng thì
 * tệ hơn — chụp trễ là chụp kết quả sau {@code freeze_at}. Hai ca dưới đỏ trên bản ấy.
 */
class NhipTungKyThiTest {

    private static final Instant BAY_GIO = Instant.parse("2026-10-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(BAY_GIO, ZoneOffset.UTC);

    private final ContestGia contests = new ContestGia();
    private final StandingsGia standings = new StandingsGia();

    @Test
    @DisplayName("★ kỳ thi đầu ném lỗi thì kỳ thi sau VẪN được cập nhật bảng xếp hạng")
    void cap_nhat_ky_thi_sau_du_ky_thi_dau_hong() {
        contests.danhSach = List.of(kyThi(1), kyThi(2));
        KhoaGia khoa = new KhoaGia(1L);
        StandingsUpdater updater = new StandingsUpdater(contests, standings, khoa,
                new JudgingGia(), new BusGia(), AppPropertiesGia.macDinh(), CLOCK, new TxGia());

        assertThatCode(updater::nhip).doesNotThrowAnyException();

        assertThat(khoa.daKhoa)
                .as("kỳ thi 2 phải tới lượt dù kỳ thi 1 vừa ném")
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("★ kỳ thi đầu ném lỗi thì kỳ thi sau VẪN được chụp đóng băng đúng giờ")
    void chup_ky_thi_sau_du_ky_thi_dau_hong() {
        contests.danhSach = List.of(kyThi(1), kyThi(2));
        standings.nemKhiChup = 1L;
        FreezeStandingsScheduler nhip = new FreezeStandingsScheduler(contests, standings, CLOCK);

        assertThatCode(nhip::nhip).doesNotThrowAnyException();

        assertThat(standings.daChup)
                .as("chụp trễ là lộ kết quả sau freeze_at — kỳ thi 2 không được chờ kỳ thi 1")
                .containsExactly(2L);
    }

    @Test
    @DisplayName("không đọc được danh sách kỳ thi thì nuốt, không ném ra bộ lập lịch")
    void doc_danh_sach_hong_thi_nuot() {
        contests.nem = true;
        StandingsUpdater updater = new StandingsUpdater(contests, standings, new KhoaGia(null),
                new JudgingGia(), new BusGia(), AppPropertiesGia.macDinh(), CLOCK, new TxGia());

        assertThatCode(updater::nhip).doesNotThrowAnyException();
        assertThatCode(new FreezeStandingsScheduler(contests, standings, CLOCK)::nhip)
                .doesNotThrowAnyException();
    }

    // =========================================================================

    private static Contest kyThi(long id) {
        return new Contest(id, "thi-" + id, "Thi " + id, ContestFormats.tuMa("ICPC"),
                BAY_GIO.minus(Duration.ofHours(1)), BAY_GIO.plus(Duration.ofHours(1)),
                BAY_GIO.minus(Duration.ofMinutes(5)), null, 20, true, true, 1L);
    }

    private static final class KhoaGia implements StandingsLock {
        final List<Long> daKhoa = new ArrayList<>();
        final Long nemCho;

        KhoaGia(Long nemCho) {
            this.nemCho = nemCho;
        }

        @Override
        public void khoaKyThi(long contestId) {
            daKhoa.add(contestId);
            if (nemCho != null && nemCho == contestId) {
                throw new IllegalStateException("dữ liệu lạ của contest " + contestId);
            }
        }
    }

    private static final class ContestGia implements ContestRepository {
        List<Contest> danhSach = List.of();
        boolean nem;

        @Override
        public List<Contest> canCapNhat(Instant bayGio, Duration anHan) {
            return doc();
        }

        @Override
        public List<Contest> canDongBang(Instant bayGio) {
            return doc();
        }

        private List<Contest> doc() {
            if (nem) {
                throw new IllegalStateException("database dỗi");
            }
            return danhSach;
        }

        @Override public Optional<Contest> timTheoId(long c) { throw chuaCan(); }
        @Override public Optional<Contest> timTheoSlug(String s) { throw chuaCan(); }
        @Override public long tao(ContestMoi c) { throw chuaCan(); }
        @Override public List<DeCuaContest> deCua(long c) { throw chuaCan(); }
        @Override public boolean daDangKy(long c, long u) { throw chuaCan(); }
        @Override public void dangKy(long c, long u, Instant l) { throw chuaCan(); }
        @Override public List<Contest> danhSach(Long s, int g) { throw chuaCan(); }
        @Override public void congBo(long c, Instant l) { throw chuaCan(); }
    }

    private static final class StandingsGia implements StandingsRepository {
        final List<Long> daChup = new ArrayList<>();
        Long nemKhiChup;

        @Override public long watermark(long c) { return 0; }

        @Override public boolean daChupDongBang(long c) { return false; }

        @Override
        public void chupDongBang(long c, Instant freezeAt) {
            if (nemKhiChup != null && nemKhiChup == c) {
                throw new IllegalStateException("dữ liệu lạ của contest " + c);
            }
            daChup.add(c);
        }

        @Override public Map<Long, List<KetQuaDe>> ketQuaTheoNguoi(long c, Collection<Long> u) {
            throw chuaCan();
        }
        @Override public void ghiKetQuaDe(long c, long u, KetQuaDe k) { throw chuaCan(); }
        @Override public void ghiTongKet(long c, long u, TongKet t, int p, long b) {
            throw chuaCan();
        }
        @Override public void xoaBangXepHang(long c) { throw chuaCan(); }
        @Override public Map<Long, DiemDaLuu> tongKetDaLuu(long c) { throw chuaCan(); }
        @Override public void ghiDrift(long c, int k, int l, Map<String, Object> ct) {
            throw chuaCan();
        }
        @Override public List<Long> canSoatLech(Instant b, Instant k, Instant c) {
            throw chuaCan();
        }
    }

    /** Lô rỗng: test này hỏi kỳ thi nào được tới lượt, không hỏi lô được tính thế nào. */
    private static final class JudgingGia implements JudgingQueries {
        @Override
        public List<ScoredSubmission> baiDaChamLienMach(long c, long sau, int gioiHan) {
            return List.of();
        }

        @Override
        public List<ScoredSubmission> baiDaChamTrongContest(long c, long sau, int gioiHan) {
            throw chuaCan();
        }

        @Override
        public List<ScoredSubmission> baiDaChamCuaDe(long c, long p, long sau, int gioiHan) {
            throw chuaCan();
        }
    }

    private static final class BusGia implements StandingsEventBus {
        @Override public void bangDaDoi(long contestId) { }
        @Override public AutoCloseable subscribe(long contestId, Runnable khiDoi) {
            throw chuaCan();
        }
    }

    private static final class TxGia implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition d) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus s) { }
        @Override public void rollback(TransactionStatus s) { }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
