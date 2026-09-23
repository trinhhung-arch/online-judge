package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.security.CurrentUserProvider.CurrentUser;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.port.ProblemAuthoringRepository;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phần của quyền sở hữu nằm ở USE-CASE: ai được đưa vào câu SQL, và kiểm theo thứ tự nào.
 * Bản thân điều kiện SQL được đo trên Postgres thật ở {@code ContestChuSoHuuIT}.
 *
 * <p>Fake repository mô phỏng đúng ngữ nghĩa của câu SQL ({@code created_by = nguoiGoi OR
 * laAdmin}), nên một use-case truyền sai người — ví dụ truyền {@code contest.createdBy()} thay
 * cho người gọi — sẽ qua được fake và đỏ ở đây.
 */
class AuthorContestUseCaseTest {

    private static final Instant BAY_GIO = Instant.parse("2026-09-23T10:00:00Z");
    private static final long KY_CUA_CHU = 10L;
    private static final long CHU = 2L;
    private static final long KE_KHAC = 7L;

    private final SoanKyThiGia soan = new SoanKyThiGia();
    private final DeGia de = new DeGia();

    private AuthorContestUseCase useCase(CurrentUser nguoiGoi) {
        Clock dongHo = Clock.fixed(BAY_GIO, ZoneOffset.UTC);
        var authorProblem = new AuthorProblemUseCase(() -> nguoiGoi, de,
                (h, l, id, ct) -> { }, new KhongKyThiNao(), dongHo);
        // ContestRepository chỉ phục vụ `tao` — không ca nào ở đây gọi tới.
        return new AuthorContestUseCase(() -> nguoiGoi, null, soan, authorProblem,
                (h, l, id, ct) -> { }, dongHo);
    }

    @Test
    @DisplayName("★ NGƯỜI GỌI — không phải chủ kỳ thi — là thứ đi tới tận câu SQL")
    void nguoi_goi_di_toi_cau_sql() {
        useCase(new CurrentUser(CHU, "setter", Role.SETTER)).themDe(KY_CUA_CHU, 1L, "A", 100);
        useCase(new CurrentUser(3L, "admin", Role.ADMIN)).themDe(KY_CUA_CHU, 1L, "B", 100);

        assertThat(soan.loiGoi).containsExactly("themDe 10/1 bởi 2 admin=false",
                "themDe 10/1 bởi 3 admin=true");
    }

    @Test
    @DisplayName("★ ADMIN đã bị hạ vì chưa bật 2FA KHÔNG mang quyền admin xuống SQL (ADR 017)")
    void admin_da_ha_khong_mang_quyen_xuong_sql() {
        var daHa = new CurrentUser(3L, "admin", Role.SETTER, true);

        assertThatThrownBy(() -> useCase(daHa).themDe(KY_CUA_CHU, 1L, "A", 100))
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
    }

    @Test
    @DisplayName("★ kỳ thi của người khác → 404 TRƯỚC khi soạn đề: không sinh ra đề nào")
    void soan_de_rieng_kiem_quyen_truoc_khi_tao_de() {
        var keKhac = useCase(new CurrentUser(KE_KHAC, "ke-khac", Role.SETTER));

        assertThatThrownBy(() -> keKhac.soanDeRieng(KY_CUA_CHU, lenhDe(), "A", 100))
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
        assertThat(de.soLanTao).isZero();
        assertThat(soan.loiGoi).isEmpty();
    }

    @Test
    @DisplayName("kỳ thi của người khác → 404 trước cả lỗi nhãn: không nói gì về kỳ thi ấy")
    void quyen_dung_truoc_moi_phep_kiem_khac() {
        var keKhac = useCase(new CurrentUser(KE_KHAC, "ke-khac", Role.SETTER));

        assertThatThrownBy(() -> keKhac.themDe(KY_CUA_CHU, 1L, "nhãn sai", -5))
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
    }

    @Test
    @DisplayName("gỡ đề khỏi kỳ thi của người khác → 404, không gọi tới câu DELETE")
    void go_de_ky_thi_nguoi_khac() {
        var keKhac = useCase(new CurrentUser(KE_KHAC, "ke-khac", Role.SETTER));

        assertThatThrownBy(() -> keKhac.goDeKhoiKyThi(KY_CUA_CHU, 1L))
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
        assertThat(soan.loiGoi).isEmpty();
    }

    // ---- fake ----------------------------------------------------------------------------

    private static AuthorProblemUseCase.Command lenhDe() {
        return new AuthorProblemUseCase.Command("DE-MOI", "Đề mới", "x", 1000, 262_144,
                dev.oj.contract.CheckerType.TOKEN, null,
                dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false);
    }

    /** Một kỳ thi chưa mở, của {@link #CHU}; ngữ nghĩa khớp {@code JdbcContestAuthoringRepository}. */
    private static final class SoanKyThiGia implements ContestAuthoringRepository {
        final List<String> loiGoi = new ArrayList<>();

        @Override
        public Optional<Contest> timDeSoan(long contestId, long nguoiGoi, boolean laAdmin) {
            if (contestId != KY_CUA_CHU || !(nguoiGoi == CHU || laAdmin)) {
                return Optional.empty();
            }
            return Optional.of(new Contest(contestId, "ky", "Kỳ thi", ContestFormats.tuMa("ICPC"),
                    BAY_GIO.plus(Duration.ofHours(1)), BAY_GIO.plus(Duration.ofHours(4)),
                    null, null, 20, true, true, CHU));
        }

        @Override
        public void themDe(long c, long p, String l, int d, long nguoiGoi, boolean laAdmin) {
            if (timDeSoan(c, nguoiGoi, laAdmin).isEmpty()) {
                throw ContestsException.khongHopLe("contest.de_khong_ton_tai", "fake");
            }
            loiGoi.add("themDe " + c + "/" + p + " bởi " + nguoiGoi + " admin=" + laAdmin);
        }

        @Override
        public void themDeSoanRieng(long c, long p, String l, int d, long nguoiGoi,
                                    boolean laAdmin) {
            loiGoi.add("themDeSoanRieng " + c + "/" + p + " bởi " + nguoiGoi);
        }

        @Override
        public boolean goDe(long c, long p, long nguoiGoi, boolean laAdmin) {
            loiGoi.add("goDe " + c + "/" + p + " bởi " + nguoiGoi);
            return true;
        }
    }

    /** Chỉ đếm số đề được tạo — thứ duy nhất ca "không để lại đề mồ côi" cần. */
    private static final class DeGia implements ProblemAuthoringRepository {
        int soLanTao;

        @Override
        public long taoMoi(NewProblem problem) {
            return ++soLanTao;
        }

        @Override public Optional<dev.oj.problems.domain.Problem> findForAuthor(
                String code, long r, boolean a) { throw new UnsupportedOperationException(); }
        @Override public Optional<dev.oj.problems.domain.Problem> findForAuthorById(
                long id, long r, boolean a) { throw new UnsupportedOperationException(); }
        @Override public boolean capNhat(long id, ProblemEdit s, long r, boolean a) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean coBaiNop(long problemId) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean xoa(long id, long r, boolean a) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean doiTrangThai(long id, dev.oj.problems.domain.ProblemStatus m,
                                              long r, boolean a, Instant luc) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class KhongKyThiNao implements ContestWindowQuery {
        @Override public OptionalLong contestDangChayChuaDe(long p) { return OptionalLong.empty(); }
        @Override public boolean coKyThiDangChay() { return false; }
        @Override public boolean deBiKhoaBoiLichThi(long p, Long u, boolean r) { return false; }
        @Override public List<Long> deBiKhoaChoNguoiXem(Long u) { return List.of(); }
        @Override public boolean deNamTrongKyThiNaoDo(long p) { return false; }
    }
}
