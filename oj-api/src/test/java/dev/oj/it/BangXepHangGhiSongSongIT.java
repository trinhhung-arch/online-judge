package dev.oj.it;

import dev.oj.contests.application.RebuildStandingsJob;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsLock;
import dev.oj.contests.domain.Contest;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ <b>Hai lượt ghi bảng xếp hạng cùng một kỳ thi không được chen vào nhau.</b> FR-CON-04, FR-CON-08.
 *
 * <p>{@code StandingsUpdater.capNhat} đọc watermark, đọc lô bài, đọc kết quả từng đề, rồi mới
 * ghi. Hai lượt chạy cùng lúc — nhịp 2 giây và {@code RebuildStandingsJob}, hoặc hai instance
 * API ({@code nfrplan.md} Phần 14) — từng đọc chung một watermark, và lượt đến sau áp lại cả
 * lô lên trạng thái mà lượt trước vừa ghi. Với ICPC, một lần sai thành hai: thí sinh mất thêm
 * {@code penalty_minutes} cho một bài họ chỉ nộp một lần, và không ai thấy cho tới khi có
 * người khiếu nại thứ hạng.
 *
 * <h2>Vì sao phải dàn cảnh chứ không chỉ thả hai luồng rồi cầu may</h2>
 * Cửa sổ của race này rộng vài mili giây; thả tự do thì test xanh gần như mọi lần trên bản lỗi,
 * tức là không chứng minh được gì. {@link DiemHenSql} dừng một luồng ngay trước câu đọc kết quả
 * từng đề ({@link #CAU_DOC_KET_QUA}) cho tới khi luồng kia đã chen vào — hoặc tới khi luồng
 * kia <b>đứng chờ khoá</b>, dấu hiệu bản đã sửa đang xếp hai lượt thành hàng. Cả hai ca race
 * dưới đây đều ĐỎ trên bản trước khi có khoá: {@code 2} lần sai thay vì 1, và {@code 0} thay vì 1.
 *
 * <h2>Kỳ thi được giấu khỏi nhịp 2 giây, có chủ ý</h2>
 * {@code oj.contest.standings-interval} không đặt được quá 2 giây ({@code ContestProperties}),
 * nên bộ lập lịch vẫn chạy. Nếu nó xử lý trước chính lô mà hai luồng cần tranh nhau, race không
 * xảy ra và test xanh trên bản lỗi. Nên bài được nộp khi kỳ thi đang mở (để mang
 * {@code contest_id}), rồi khung giờ bị dời về quá khứ — ra ngoài {@code canCapNhat} —
 * <b>trước</b> khi có bài nào {@code DONE}.
 */
class BangXepHangGhiSongSongIT extends PostgresIT {

    /** Câu đọc kết quả từng đề — {@code KET_QUA_THEO_NGUOI} của {@code JdbcStandingsRepository}. */
    private static final String CAU_DOC_KET_QUA = "AND user_id IN (";

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsUpdater updater;
    @Autowired StandingsLock khoa;
    @Autowired RebuildStandingsJob rebuild;
    @Autowired SubmitSolutionUseCase submitSolution;

    private final List<Throwable> loiTrongLuong = new CopyOnWriteArrayList<>();

    private long contestId;

    @BeforeEach
    void dungKyThiDangMo() {
        contestId = contests.tao(new ContestRepository.ContestMoi(
                "thi-dua-" + System.nanoTime(), "Thi đua ghi", "ICPC",
                Instant.now().minus(Duration.ofHours(1)),
                Instant.now().plus(Duration.ofHours(2)), null,
                20, true, true, ADMIN_ID));
        soanKyThi.themDe(contestId, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        contests.dangKy(contestId, USER_ID, Instant.now());
    }

    @AfterEach
    void huyDiemHen() {
        DiemHenSql.huyHet();
    }

    // =========================================================================

    @Test
    @DisplayName("★ hai lượt cập nhật chen nhau KHÔNG đếm một lần sai thành hai")
    void hai_luot_cap_nhat_khong_dem_trung() throws InterruptedException {
        long wa = nop();
        giauKhoiNhipHaiGiay();
        cham(wa, "WA", 0);

        CountDownLatch bDaToi = new CountDownLatch(1);
        CountDownLatch aXong = new CountDownLatch(1);
        Thread b = luong("dua-B", () -> updater.capNhat(contest()));
        Thread a = luong("dua-A", () -> {
            cho(bDaToi);
            updater.capNhat(contest());
            aXong.countDown();
        });
        // B đã đọc watermark và lô [WA], dừng trước câu đọc kết quả. A chạy trọn lượt của nó
        // — hoặc đứng chờ khoá mà B đang giữ.
        DiemHenSql.hen(b, CAU_DOC_KET_QUA, bDaToi,
                () -> aXong.getCount() == 0 || coLuotDangChoKhoa());

        chayVaChoXong(b, a);

        assertThat(soLanSai())
                .as("một bài WA chỉ được tính MỘT lần sai — hai là thí sinh mất thêm 20 phút "
                        + "penalty cho một bài họ nộp một lần")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ dựng lại chờ lượt cập nhật đang ghi, không xoá chen vào giữa nó")
    void dung_lai_cho_luot_dang_ghi() throws InterruptedException {
        long wa = nop();
        long ac = nop();
        giauKhoiNhipHaiGiay();
        cham(wa, "WA", 0);
        updater.capNhat(contest());          // WA đã vào bảng
        cham(ac, "AC", 100);                 // AC chưa vào

        CountDownLatch bDaToi = new CountDownLatch(1);
        CountDownLatch rDaToi = new CountDownLatch(1);
        CountDownLatch bXong = new CountDownLatch(1);
        Thread b = luong("dua-B", () -> {
            updater.capNhat(contest());
            bXong.countDown();
        });
        Thread r = luong("dua-R", () -> {
            cho(bDaToi);
            rebuild.chay(new JobContextGia(Map.of("contestId", contestId), ADMIN_ID));
        });
        // B đã đọc lô [AC] và dừng; R xoá bảng rồi tới chính câu đọc kết quả của nó. Bản lỗi:
        // B ghi lên bảng vừa bị xoá, watermark nhảy tới AC, và lần sai WA biến khỏi bảng.
        DiemHenSql.hen(b, CAU_DOC_KET_QUA, bDaToi,
                () -> rDaToi.getCount() == 0 || coLuotDangChoKhoa());
        DiemHenSql.hen(r, CAU_DOC_KET_QUA, rDaToi,
                () -> bXong.getCount() == 0 || coLuotDangChoKhoa());

        chayVaChoXong(b, r);

        assertThat(daDat()).isTrue();
        assertThat(soLanSai())
                .as("lần sai trước AC phải còn sau khi dựng lại — mất nó là mất 20 phút penalty")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("khoá gọi ngoài transaction thì NÉM — không im lặng khoá rồi nhả ngay")
    void khoa_ngoai_transaction_thi_nem() {
        assertThatThrownBy(() -> khoa.khoaKyThi(contestId))
                .isInstanceOf(IllegalStateException.class);
    }

    // =========================================================================

    /** Có lượt nào đang đứng chờ một khoá tư vấn (advisory lock) không. */
    private boolean coLuotDangChoKhoa() {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM pg_locks WHERE locktype = 'advisory' AND NOT granted)
                """).query(Boolean.class).single());
    }

    private Thread luong(String ten, Runnable viec) {
        return new Thread(() -> {
            try {
                viec.run();
            } catch (Throwable e) {
                loiTrongLuong.add(e);
            }
        }, ten);
    }

    private void chayVaChoXong(Thread... cac) throws InterruptedException {
        for (Thread t : cac) {
            t.start();
        }
        for (Thread t : cac) {
            t.join(DiemHenSql.TRAN_CHO.multipliedBy(3).toMillis());
            assertThat(t.isAlive()).as("luồng %s treo", t.getName()).isFalse();
        }
        assertThat(loiTrongLuong).as("lỗi trong luồng đua").isEmpty();
    }

    private static void cho(CountDownLatch latch) {
        try {
            if (!latch.await(DiemHenSql.TRAN_CHO.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("luồng kia không tới điểm hẹn sau " + DiemHenSql.TRAN_CHO
                        + " — câu SQL ở CAU_DOC_KET_QUA đã bị viết lại?");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** Dời khung giờ về quá khứ: {@code canCapNhat} không còn thấy kỳ thi này. */
    private void giauKhoiNhipHaiGiay() {
        jdbc.sql("""
                UPDATE contests
                   SET starts_at = now() - interval '3 hours', ends_at = now() - interval '2 hours'
                 WHERE id = :id
                """).param("id", contestId).update();
    }

    private Contest contest() {
        return contests.timTheoId(contestId).orElseThrow();
    }

    private long nop() {
        try (var phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
            quenLuotNopVuaRoi(USER_ID);
            assertThat(phien).isNotNull();
            return submitSolution.submit(new SubmitSolutionUseCase.Command(
                    PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        }
    }

    private void cham(long id, String verdict, int score) {
        jdbc.sql("""
                UPDATE submissions
                   SET status = 'DONE', verdict = :v, score = :s, max_score = 100, judged_at = now()
                 WHERE id = :id
                """).param("v", verdict).param("s", score).param("id", id).update();
    }

    private int soLanSai() {
        return jdbc.sql("""
                SELECT attempts_before_score FROM contest_problem_standings
                 WHERE contest_id = :c AND user_id = :u AND problem_id = :p
                """).param("c", contestId).param("u", USER_ID).param("p", PROBLEM_ID)
                .query(Integer.class).single();
    }

    private boolean daDat() {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT first_solved_at IS NOT NULL FROM contest_problem_standings
                 WHERE contest_id = :c AND user_id = :u AND problem_id = :p
                """).param("c", contestId).param("u", USER_ID).param("p", PROBLEM_ID)
                .query(Boolean.class).single());
    }
}
