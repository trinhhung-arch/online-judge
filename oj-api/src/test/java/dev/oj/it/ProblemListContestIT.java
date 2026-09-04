package dev.oj.it;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ FR-CON-03 + FR-CON-07 — đề của kỳ thi phải VẮNG MẶT khỏi trang Đề bài, không phải
 * "hiện ra rồi bấm vào thì 404".
 *
 * <h2>Vì sao 404 thôi là chưa đủ</h2>
 * {@code GetProblemUseCase} đã chặn nội dung đề từ M5. Nhưng danh sách thì không lọc, nên đề
 * của kỳ thi tuần sau vẫn nằm giữa trang Đề bài như một đề bình thường. Hai thiệt hại:
 *
 * <ul>
 *   <li>Thí sinh bấm vào một dòng có thật và nhận một trang không có thật. Không lời giải
 *       thích nào là đúng ở đó — lời giải thích đúng lại là thứ FR-CON-03 cấm nói.</li>
 *   <li><b>Danh sách ấy chính là thành phần kỳ thi.</b> Bấm thử từng đề, ghi lại đề nào 404,
 *       là biết trước bộ đề. Chốt 404 giữ được nội dung đề mà không giữ được danh sách đề —
 *       và với một hệ thống bán sự công bằng thì danh sách cũng đã là quá nhiều.</li>
 * </ul>
 *
 * <h2>Tách theo THỜI GIAN, không phải theo kho riêng</h2>
 * {@link #de_quay_lai_sau_khi_ky_thi_ket_thuc()} là ca giữ FR-CON-07 <i>"sau khi contest kết
 * thúc: mở đề ra ngoài"</i>. Một kho đề riêng cho kỳ thi sẽ làm ca đó không bao giờ xanh
 * được, và kho luyện tập thì không bao giờ lớn lên.
 */
class ProblemListContestIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired ListProblemsUseCase listProblems;
    @Autowired AuthorProblemUseCase authorProblem;

    private static final Instant MOC = Instant.now();

    /** Đề 1 (A-PLUS-B) thuộc {@code SETTER_ID} theo seed dev. */
    private long dungContestChua(long problemId, Instant batDau, Instant ketThuc) {
        long id = contests.tao(new ContestRepository.ContestMoi(
                "ds-de-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, null, 20, true, true, ADMIN_ID));
        contests.themDe(id, problemId, "A", 1, 100);
        return id;
    }

    private List<String> maDeThayBoi(long userId, String handle, Role vaiTro) {
        try (var phien = GiaLapDanhTinh.dongVai(userId, handle, vaiTro)) {
            assertThat(phien).isNotNull();
            return listProblems.thucHien(null, null, null, 50).items()
                    .stream().map(d -> d.code()).toList();
        }
    }

    private List<String> maDeThayBoiThiSinh() {
        return maDeThayBoi(USER_ID, "dev", Role.USER);
    }

    // =========================================================================

    @Test
    @DisplayName("★ đề của kỳ thi CHƯA MỞ không nằm trong danh sách đề")
    void de_cua_ky_thi_chua_mo_vang_mat() {
        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");

        dungContestChua(PROBLEM_ID, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        assertThat(maDeThayBoiThiSinh()).doesNotContain("A-PLUS-B");
    }

    /**
     * ★ FR-CON-07. Không ai bấm nút gì cả — đề quay lại vì đồng hồ đã đi qua {@code ends_at}.
     * Đây là lý do bộ đề luyện tập vẫn lớn lên sau mỗi kỳ thi.
     */
    @Test
    @DisplayName("★ FR-CON-07 — đề tự quay lại danh sách sau khi kỳ thi kết thúc")
    void de_quay_lai_sau_khi_ky_thi_ket_thuc() {
        dungContestChua(PROBLEM_ID, MOC.minus(Duration.ofHours(4)), MOC.minus(Duration.ofHours(1)));

        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");
    }

    @Test
    @DisplayName("thí sinh ĐÃ đăng ký thấy đề trong giờ thi")
    void nguoi_da_dang_ky_thay_de_trong_gio_thi() {
        long ky = dungContestChua(PROBLEM_ID,
                MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)));

        assertThat(maDeThayBoiThiSinh()).doesNotContain("A-PLUS-B");

        contests.dangKy(ky, USER_ID, MOC);

        assertThat(maDeThayBoiThiSinh()).contains("A-PLUS-B");
    }

    /**
     * Ma trận hiển thị, dòng "Đề trong contest chưa mở": SETTER (đề của mình) ✅, ADMIN ✅.
     * Chốt cho SETTER nằm trong câu SQL ({@code p.owner_id = :requesterId}), không ở một câu
     * {@code if} sau khi đã load — mẫu chống IDOR của {@code oj-api/CLAUDE.md} mục 2.
     */
    @Test
    @DisplayName("người ra đề và ADMIN vẫn thấy đề bị khoá")
    void nguoi_ra_de_va_admin_van_thay() {
        dungContestChua(PROBLEM_ID, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        assertThat(maDeThayBoi(SETTER_ID, "setter", Role.SETTER)).contains("A-PLUS-B");
        assertThat(maDeThayBoi(ADMIN_ID, "admin", Role.ADMIN)).contains("A-PLUS-B");
    }

    /**
     * ★ Ca này là lý do bộ lọc phải nằm TRONG câu query.
     *
     * <p>Lọc sau khi đã lấy đủ {@code size} dòng thì trang co lại — xin 2 đề, bị khoá mất 1,
     * nhận về 1 — trong khi con trỏ trang vẫn nhảy như thể đã trả 2. Người dùng mất một đề và
     * không có dấu hiệu nào cho thấy điều đó đã xảy ra.
     */
    @Test
    @DisplayName("★ phân trang không co lại khi có đề bị khoá")
    void phan_trang_khong_co_lai() {
        long b = deMoi("ds-b-" + System.nanoTime());
        long c = deMoi("ds-c-" + System.nanoTime());
        assertThat(c).isGreaterThan(b);

        dungContestChua(c, MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));

        try (var phien = GiaLapDanhTinh.dongVai(USER_ID, "dev", Role.USER)) {
            assertThat(phien).isNotNull();
            var trang = listProblems.thucHien(null, null, null, 2);
            assertThat(trang.items())
                    .describedAs("xin 2 đề thì phải nhận đủ 2, không phải 1 vì một đề bị khoá")
                    .hasSize(2);
            assertThat(trang.items()).noneSatisfy(
                    d -> assertThat(d.id()).isEqualTo(c));
        }
    }

    /**
     * Đề mới, đã xuất bản — chỉ đề PUBLISHED mới vào danh sách.
     *
     * <p>Bản testdata được đóng dấu bằng SQL vì {@code xuatBan} đòi
     * {@code current_testdata_version > 0} và nạp một gói ZIP thật ở đây là kéo cả đường
     * import vào một ca kiểm không hỏi gì về nó. Bước đổi trạng thái thì vẫn đi qua use-case
     * thật — đó mới là thứ ca này phụ thuộc vào.
     */
    private long deMoi(String ma) {
        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            long id = authorProblem.tao(new AuthorProblemUseCase.Command(
                    ma, "Đề " + ma, "Nội dung đề " + ma, 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
            jdbc.sql("UPDATE problems SET current_testdata_version = 1 WHERE id = :id")
                    .param("id", id).update();
            authorProblem.xuatBan(id);
            return id;
        }
    }
}
