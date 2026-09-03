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

/**
 * ★ Danh tính phải sống sót qua ranh giới thread — FR-CON-04, FR-CON-05.
 *
 * <h2>Lỗi mà file này canh</h2>
 * {@code StandingsSseController} đẩy khung đầu trên thread của request, rồi đẩy mọi khung sau
 * từ thread điều phối của {@code RedisMessageListenerContainer}. Danh tính người gọi nằm
 * trong một {@code ThreadLocal} mà {@code JwtAuthFilter} đặt rồi xoá — nên trên thread thứ hai
 * nó rỗng.
 *
 * <p>Hậu quả không hề vô hại: {@code cuaToi} thành {@code null} kể từ khung thứ hai, và dòng
 * của chính thí sinh <b>biến mất</b> khỏi bảng ngay ở lần cập nhật đầu tiên — trên đúng trang
 * người ta nhìn nhiều nhất trong cả kỳ thi. Trước bản sửa này không có test nào chạm đường
 * SSE của bảng xếp hạng, nên lỗi sống sót qua toàn bộ M5.
 *
 * <p>Mỗi ca dưới đây chạy <b>cặp đôi</b>: bản có người gọi tường minh phải đúng, và bản đọc
 * {@code ThreadLocal} phải sai. Chỉ khẳng định vế đầu thì test vẫn xanh nếu ai đó lỡ tay đưa
 * lời gọi trong lambda về lại bản không tham số.
 */
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

    // =========================================================================

    @Test
    @DisplayName("★ dòng của mình KHÔNG biến mất khi khung được đẩy từ thread khác")
    void cua_toi_song_sot_qua_ranh_gioi_thread() throws Exception {
        currentUser.dat(THI_SINH);

        // Chỗ gọi phân giải danh tính TRÊN thread request, rồi giữ lại — đúng như SSE làm.
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

        // Đây chính là lỗi cũ. Nếu ngày nào đó nó không còn đúng thì `thucHien(long)` đã an
        // toàn trên mọi thread, và cả file này nên được đọc lại.
        assertThat(bang.cuaToi()).isNull();
        assertThat(bang.hangCuaToi()).isNull();
    }

    @Test
    @DisplayName("★ ADMIN vẫn thấy bảng THẬT ở khung đẩy từ thread khác")
    void admin_giu_bang_that_qua_ranh_gioi_thread() throws Exception {
        currentUser.dat(QUAN_TRI);
        var nguoiGoi = useCase.nguoiGoiHienTai();

        assertThat(oThreadKhac(() -> useCase.thucHien(1L, nguoiGoi)).dongBang()).isFalse();

        // Cùng thread ấy, bản không tham số coi admin là khán giả và trả bảng đã đóng băng.
        // Hướng sai này KHÔNG rò rỉ gì — nó giấu bớt — nên nó sống sót lâu đến vậy.
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
        // Bốn ca trên khẳng định use-case CÓ THỂ dùng đúng; ca này khẳng định chỗ gọi thật sự
        // DÙNG đúng. Thiếu nó thì đưa lời gọi trong lambda về `thucHien(contestId)` vẫn xanh —
        // và lỗi quay lại y nguyên, ở đúng chỗ nó vừa được sửa.
        String nguon = boBinhLuan(Files.readString(Path.of("src", "main", "java", "dev", "oj",
                "contests", "api", "StandingsSseController.java")));

        assertThat(nguon)
                .as("phân giải danh tính phải xảy ra trên thread của request")
                .contains("getStandings.nguoiGoiHienTai()");
        assertThat(nguon)
                .as("bản không tham số đọc ThreadLocal — nó rỗng trên thread của Redis")
                .doesNotContain("thucHien(contestId)");
    }

    /**
     * Bình luận trong chính file ấy GIẢI THÍCH lỗi cũ, nên nó chứa đúng chuỗi bị cấm. Quét cả
     * bình luận thì test đỏ vì một câu văn — và cách sửa rẻ nhất khi đó là xoá lời giải thích,
     * tức là test tự ăn mất thứ nó đang bảo vệ.
     */
    private static String boBinhLuan(String nguon) {
        return nguon.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    // =========================================================================

    private static <T> T oThreadKhac(Callable<T> viec) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            return thread.submit(viec).get();
        } finally {
            thread.shutdownNow();
        }
    }

    /** Bắt chước {@code CurrentUserHolder}: một {@code ThreadLocal}, ném khi rỗng. */
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
        @Override public void themDe(long c, long p, String l, int o, int d) { throw chuaCan(); }
        @Override public List<DeCuaContest> deCua(long contestId) { throw chuaCan(); }
        @Override public boolean daDangKy(long contestId, long userId) { throw chuaCan(); }
        @Override public void dangKy(long c, long u, Instant luc) { throw chuaCan(); }
        @Override public List<Contest> danhSach(Long sauId, int gioiHan) { throw chuaCan(); }
        @Override public List<Contest> canCapNhat(Instant t, Duration d) { throw chuaCan(); }
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
