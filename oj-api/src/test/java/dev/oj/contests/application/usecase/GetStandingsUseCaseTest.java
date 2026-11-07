package dev.oj.contests.application.usecase;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.domain.ContestFormats;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.security.AuthorizationException;
import dev.oj.platform.security.CurrentUserProvider;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class GetStandingsUseCaseTest {

    private static final Instant BAT_DAU = Instant.parse("2026-05-01T09:00:00Z");
    private static final Instant DONG_BANG = BAT_DAU.plus(Duration.ofHours(4));
    private static final Instant BAY_GIO = BAT_DAU.plus(Duration.ofHours(4).plusMinutes(30));

    private static final CurrentUserProvider.CurrentUser THI_SINH =
            new CurrentUserProvider.CurrentUser(7L, "an", Role.USER);
    private static final CurrentUserProvider.CurrentUser QUAN_TRI =
            new CurrentUserProvider.CurrentUser(9L, "sep", Role.ADMIN);

    private final CurrentUserGia currentUser = new CurrentUserGia();
    private final GetStandingsUseCase useCase = new GetStandingsUseCase(
            currentUser, new ContestGia(), new StandingsGia(),
            AppPropertiesGia.macDinh(), Clock.fixed(BAY_GIO, ZoneOffset.UTC));

    @Test
    @DisplayName("★ dòng của mình KHÔNG biến mất khi khung được đẩy từ thread khác")
    void cua_toi_song_sot_qua_ranh_gioi_thread() throws Exception {
        currentUser.dat(THI_SINH);

        var nguoiGoi = useCase.nguoiGoiHienTai();
        assertThat(nguoiGoi).isEqualTo(THI_SINH);

        var bang = oThreadKhac(() -> useCase.thucHien(1L, nguoiGoi));
        assertThat(bang.cuaToi()).isNotNull();
        assertThat(bang.cuaToi().userId()).isEqualTo(7L);
        assertThat(bang.hangCuaToi()).isEqualTo(42);
    }

    @Test
    @DisplayName("bản không tham số MẤT danh tính ở thread khác — lý do bản kia tồn tại")
    void ban_khong_tham_so_mat_danh_tinh_o_thread_khac() throws Exception {
        currentUser.dat(THI_SINH);

        var bang = oThreadKhac(() -> useCase.thucHien(1L));

        assertThat(bang.cuaToi()).isNull();
        assertThat(bang.hangCuaToi()).isNull();
    }

    @Test
    @DisplayName("★ ADMIN vẫn thấy bảng THẬT ở khung đẩy từ thread khác")
    void admin_giu_bang_that_qua_ranh_gioi_thread() throws Exception {
        currentUser.dat(QUAN_TRI);
        var nguoiGoi = useCase.nguoiGoiHienTai();

        assertThat(oThreadKhac(() -> useCase.thucHien(1L, nguoiGoi)).dongBang()).isFalse();

        assertThat(oThreadKhac(() -> useCase.thucHien(1L)).dongBang()).isTrue();
    }

    @Test
    @DisplayName("khán giả chưa đăng nhập vẫn xem được bảng, chỉ không có dòng của mình")
    void khan_gia_chua_dang_nhap_van_xem_duoc() {
        var bang = useCase.thucHien(1L, null);

        assertThat(bang.top()).hasSize(1);
        assertThat(bang.cuaToi()).isNull();
        assertThat(bang.dongBang()).isTrue();
    }

    @Test
    @DisplayName("nguoiGoiHienTai() trả null thay vì ném khi chưa đăng nhập")
    void nguoi_goi_hien_tai_tra_null_khi_chua_dang_nhap() {
        assertThat(useCase.nguoiGoiHienTai()).isNull();
    }

    @Test
    @DisplayName("★ StandingsSseController KHÔNG được gọi bản không tham số")
    void controller_sse_phai_giu_lai_nguoi_goi() throws Exception {
        String nguon = boBinhLuan(Files.readString(Path.of("src", "main", "java", "dev", "oj",
                "contests", "api", "StandingsSseController.java")));

        assertThat(nguon)
                .as("phân giải danh tính phải xảy ra trên thread của request")
                .contains("getStandings.nguoiGoiHienTai()");
        assertThat(nguon)
                .as("bản không tham số đọc ThreadLocal — nó rỗng trên thread của Redis")
                .doesNotContain("thucHien(contestId)");
    }

    private static String boBinhLuan(String nguon) {
        return nguon.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    private static <T> T oThreadKhac(Callable<T> viec) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(viec).get();
        } finally {
            thread.shutdownNow();
        }
    }

    private static final class CurrentUserGia implements CurrentUserProvider {
        private final ThreadLocal<CurrentUser> hienTai = new ThreadLocal<>();

        void dat(CurrentUser nguoiDung) {
            hienTai.set(nguoiDung);
        }

        @Override
        public CurrentUser current() {
            CurrentUser nguoiDung = hienTai.get();
            if (nguoiDung == null) {
                throw AuthorizationException.chuaDangNhap();
            }
            return nguoiDung;
        }
    }

    private static final class ContestGia implements ContestRepository {
        @Override
        public Optional<Contest> timTheoId(long contestId) {
            return Optional.of(new Contest(contestId, "thu", "Thi thử",
                    ContestFormats.tuMa("ICPC"), BAT_DAU, BAT_DAU.plus(Duration.ofHours(5)),
                    DONG_BANG, null, 20, true, true, 1L));
        }

        @Override public Optional<Contest> timTheoSlug(String slug) { throw chuaCan(); }
        @Override public long tao(ContestMoi contest) { throw chuaCan(); }
        @Override public List<DeCuaContest> deCua(long contestId) { throw chuaCan(); }
        @Override public boolean daDangKy(long contestId, long userId) { throw chuaCan(); }
        @Override public void dangKy(long c, long u, Instant luc) { throw chuaCan(); }
        @Override public List<Contest> danhSach(Long sauId, int gioiHan) { throw chuaCan(); }
        @Override public List<Contest> canCapNhat(Instant t) { throw chuaCan(); }
        @Override public void chotBangXepHang(long c, Instant l) { throw chuaCan(); }
        @Override public List<Contest> canDongBang(Instant bayGio) { throw chuaCan(); }
        @Override public void congBo(long contestId, Instant luc) { throw chuaCan(); }
    }

    private static final class StandingsGia implements StandingsReader {
        @Override
        public List<Dong> top(long contestId, int n, boolean dongBang) {
            return List.of(new Dong(1L, "binh", "Bình", 300, 0, 3, BAY_GIO, 0));
        }

        @Override
        public Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang) {
            return Optional.of(new Dong(userId, "an", "An", 100, 60, 1, BAY_GIO, 0));
        }

        @Override
        public Optional<Integer> hang(long contestId, long userId, boolean dongBang) {
            return Optional.of(42);
        }
    }

    private static UnsupportedOperationException chuaCan() {
        return new UnsupportedOperationException("Không thuộc phạm vi test này");
    }
}
