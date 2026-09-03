package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bảng xếp hạng trên Postgres thật — M5, FR-CON-04 và FR-CON-05.
 *
 * <h2>Hai bất biến được canh ở đây</h2>
 * <ul>
 *   <li><b>Idempotent</b> — chạy lại một lô không nhân đôi điểm. {@code StandingsUpdater} SẼ
 *       chạy lại: sau restart, sau rebuild, sau khi lease hết hạn.</li>
 *   <li><b>Đóng băng lọc theo vai trò</b> — thí sinh thấy bản chụp, ADMIN thấy bảng thật. Sai
 *       chiều nào cũng hỏng: lộ bảng thì mất nghi thức trao giải, giấu nhầm ADMIN thì người
 *       tổ chức không điều hành được kỳ thi của mình.</li>
 * </ul>
 *
 * <p>Quyền truy cập nằm ở {@link ContestAccessIT}.
 */
class ContestStandingsIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired StandingsReader standings;
    @Autowired StandingsUpdater updater;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired SubmitSolutionUseCase submitSolution;

    private static final Instant MOC = Instant.now();

    /** Kỳ thi đang chạy, chứa đề A-PLUS-B. */
    private long dangChay() {
        return dungContest(MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)), null);
    }

    private long dungContest(Instant batDau, Instant ketThuc, Instant dongBang) {
        long id = contests.tao(new ContestRepository.ContestMoi(
                "thi-thu-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, dongBang, 20, true, true, ADMIN_ID));
        contests.themDe(id, PROBLEM_ID, "A", 1, 100);
        return id;
    }

    private Contest doc(long id) {
        return contests.timTheoId(id).orElseThrow();
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-CON-04 · bảng xếp hạng")
    class BangXepHang {

        private long nopVaCham(long contestId, long userId, String verdict, int score) {
            long id;
            try (var phien = GiaLapDanhTinh.dongVai(userId, "nguoi", Role.USER)) {
                quenLuotNopVuaRoi(userId);
                id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                        PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
                assertThat(phien).isNotNull();
            }
            jdbc.sql("""
                    UPDATE submissions
                       SET status = 'DONE', verdict = :verdict, score = :score, max_score = 100,
                           judged_at = now()
                     WHERE id = :id
                    """).param("verdict", verdict).param("score", score).param("id", id).update();
            return id;
        }

        /**
         * ★ Bài id NHỎ hơn nhưng chấm xong MUỘN hơn vẫn phải vào bảng.
         *
         * <h2>Ca này tái hiện một lỗi có thật, đã đo trên Postgres</h2>
         * {@code StandingsUpdater} tiến một watermark duy nhất theo {@code id}, còn lô đọc
         * trước đây lọc {@code status='DONE'}. Hai thứ đó cộng lại nghĩa là bài đang chấm dở
         * bị nhảy qua, watermark vượt lên, và <b>không lô nào đọc lại nó nữa</b>.
         *
         * <p>Trước khi sửa, ca này ra {@code lô1=1 · lô2=0 · bảng có 1 người}: bài AC của
         * người nộp trước biến mất vĩnh viễn. Đó không phải tình huống hiếm mà là tình huống
         * thường của một kỳ thi — thứ tự claim theo id, thứ tự chấm xong theo độ nặng của bài.
         *
         * <p>Bản sửa dùng {@code baiDaChamLienMach}: lô dừng ở bài chưa xong đầu tiên. Bảng
         * đứng lại một nhịp thay vì thiếu một người, và nó tự đúng lại.
         */
        @Test
        @DisplayName("★ bài id nhỏ hơn chấm xong sau vẫn vào bảng — watermark không nhảy qua")
        void watermark_khong_nhay_qua_bai_dang_cham() {
            long c = dangChay();
            contests.dangKy(c, USER_ID, MOC);
            contests.dangKy(c, SETTER_ID, MOC);

            long cham = nopChuaCham(USER_ID);      // id nhỏ hơn, chấm lâu
            long nhanh = nopChuaCham(SETTER_ID);   // id lớn hơn, xong trước
            assertThat(cham).isLessThan(nhanh);

            chamXong(nhanh);
            // Lô này phải KHÔNG áp gì: bài id nhỏ hơn còn đang chấm, tiến qua nó là mất nó.
            assertThat(updater.capNhat(doc(c)))
                    .as("lô không được vượt qua bài chưa chấm xong")
                    .isZero();

            chamXong(cham);
            assertThat(updater.capNhat(doc(c))).isEqualTo(2);

            assertThat(standings.top(c, 50, false))
                    .as("cả hai người đều phải có mặt")
                    .hasSize(2);
        }

        /** Nộp thật nhưng KHÔNG chấm — bài nằm lại ở {@code QUEUED}. */
        private long nopChuaCham(long userId) {
            try (var phien = GiaLapDanhTinh.dongVai(userId, "nguoi" + userId, Role.USER)) {
                quenLuotNopVuaRoi(userId);
                assertThat(phien).isNotNull();
                return submitSolution.submit(new SubmitSolutionUseCase.Command(
                        PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
            }
        }

        private void chamXong(long id) {
            jdbc.sql("""
                    UPDATE submissions
                       SET status = 'DONE', verdict = 'AC', score = 100, max_score = 100,
                           judged_at = now()
                     WHERE id = :id
                    """).param("id", id).update();
        }

        @Test
        @DisplayName("★ bài AC vào bảng; bài WA không")
        void ac_vao_bang() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);
            nopVaCham(id, USER_ID, "AC", 100);

            assertThat(updater.capNhat(doc(id))).isEqualTo(1);

            List<StandingsReader.Dong> top = standings.top(id, 50, false);
            assertThat(top).singleElement().satisfies(d -> {
                assertThat(d.userId()).isEqualTo(USER_ID);
                assertThat(d.soBaiDat()).isEqualTo(1);
            });
        }

        @Test
        @DisplayName("★ chạy lại cùng một lô KHÔNG nhân đôi điểm — Quy tắc 4")
        void chay_lai_khong_nhan_doi() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);
            nopVaCham(id, USER_ID, "AC", 100);

            updater.capNhat(doc(id));
            var lanDau = standings.top(id, 50, false);
            // Nhịp thứ hai: watermark đã vượt qua bài này, nên không có gì để làm.
            assertThat(updater.capNhat(doc(id))).isZero();

            assertThat(standings.top(id, 50, false)).isEqualTo(lanDau);
        }

        @Test
        @DisplayName("★ watermark tiến đúng — bài nộp sau được tính ở nhịp sau")
        void watermark_tien_dung() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);
            nopVaCham(id, USER_ID, "WA", 0);
            updater.capNhat(doc(id));

            nopVaCham(id, USER_ID, "AC", 100);
            assertThat(updater.capNhat(doc(id)))
                    .describedAs("chỉ bài MỚI được đọc, không đọc lại bài cũ")
                    .isEqualTo(1);

            assertThat(standings.top(id, 50, false)).singleElement()
                    .satisfies(d -> assertThat(d.soBaiDat()).isEqualTo(1));
        }

        @Test
        @DisplayName("★ ICPC: một lần sai trước khi AC cộng 20 phút penalty")
        void penalty_icpc() {
            long id = dangChay();
            contests.dangKy(id, USER_ID, MOC);
            nopVaCham(id, USER_ID, "WA", 0);
            nopVaCham(id, USER_ID, "AC", 100);

            updater.capNhat(doc(id));

            assertThat(standings.top(id, 50, false)).singleElement()
                    .satisfies(d -> assertThat(d.penaltyGiay())
                            .describedAs("phút tới lúc AC + 1 × 20 phút")
                            .isGreaterThanOrEqualTo(20 * 60));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ FR-CON-05 · đóng băng")
    class DongBang {

        @Test
        @DisplayName("★ sau giờ freeze, người thường thấy BẢN CHỤP còn ADMIN thấy bảng thật")
        void dong_bang_loc_theo_vai_tro() {
            long id = dungContest(MOC.minus(Duration.ofHours(2)),
                    MOC.plus(Duration.ofHours(1)), MOC.minus(Duration.ofMinutes(30)));

            // Chụp một bảng rỗng (chưa ai ghi điểm) rồi mới cho một người ghi điểm.
            jdbc.sql("""
                    INSERT INTO contest_standings (contest_id, user_id, total_score,
                        penalty_seconds, solved_count, last_applied_submission_id)
                    VALUES (:c, :u, 0, 0, 0, 0)
                    """).param("c", id).param("u", USER_ID).update();
            jdbc.sql("""
                    INSERT INTO contest_standings_frozen (contest_id, user_id, total_score,
                        penalty_seconds, solved_count)
                    VALUES (:c, :u, 0, 0, 0)
                    """).param("c", id).param("u", USER_ID).update();
            jdbc.sql("UPDATE contest_standings SET total_score = 5, solved_count = 5 "
                    + "WHERE contest_id = :c").param("c", id).update();

            try (var phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
                assertThat(getStandings.thucHien(id).dongBang()).isTrue();
                assertThat(getStandings.thucHien(id).top()).singleElement()
                        .satisfies(d -> assertThat(d.soBaiDat())
                                .describedAs("thí sinh phải thấy BẢN CHỤP, không thấy điểm mới")
                                .isZero());
                assertThat(phien).isNotNull();
            }

            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                assertThat(getStandings.thucHien(id).dongBang()).isFalse();
                assertThat(getStandings.thucHien(id).top()).singleElement()
                        .satisfies(d -> assertThat(d.soBaiDat()).isEqualTo(5));
                assertThat(phien).isNotNull();
            }
        }

        @Test
        @DisplayName("★ đóng băng KHÔNG tự hết khi hết giờ — chờ người công bố")
        void dong_bang_khong_tu_het() {
            long id = dungContest(MOC.minus(Duration.ofHours(4)),
                    MOC.minus(Duration.ofHours(1)), MOC.minus(Duration.ofHours(2)));

            // Cả điểm của nghi thức trao giải kiểu ICPC: bảng vẫn kín sau tiếng chuông.
            assertThat(doc(id).dangDongBang(Instant.now())).isTrue();
        }
    }
}
