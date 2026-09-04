package dev.oj.it;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import dev.oj.problems.domain.ProblemNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Xoá đề — và ba trường hợp KHÔNG được xoá.
 *
 * <h2>Vì sao ca từ chối nhiều hơn ca cho phép</h2>
 * "Xoá" nghe như một thao tác của người sở hữu với thứ của mình. Nó không phải: một đề đã có
 * bài nộp là <b>lịch sử của người khác</b>, và một đề trong kỳ thi là <b>một cột trong bảng
 * xếp hạng</b>. Xoá nó đi thì người đã giải không còn biết mình giải bài gì, và bảng xếp
 * hạng cũ thủng một cột không ai dựng lại được.
 *
 * <p>Còn lại đúng một trường hợp: bản nháp bỏ đi. Đó cũng là trường hợp duy nhất người ta
 * thật sự cần — soạn nhầm, gõ sai mã, tạo trùng.
 *
 * <h2>Ngõ cụt mà {@link #go_khoi_ky_thi_roi_thi_xoa_duoc()} canh</h2>
 * Trước V10 không có cách nào gỡ một đề khỏi kỳ thi. Gắn nhầm một đề là nó kẹt vĩnh viễn:
 * không ra khỏi kỳ thi được, không xoá được (chốt ở đây từ chối), và vắng mặt khỏi trang Đề
 * bài cho tới khi kỳ thi kết thúc. Ca ấy đi hết đường thoát, từ đầu đến cuối.
 */
class XoaDeIT extends PostgresIT {

    @Autowired AuthorProblemUseCase authorProblem;
    @Autowired AuthorContestUseCase authorContest;
    @Autowired ContestRepository contests;
    @Autowired dev.oj.problems.application.port.ProblemAuthoringRepository problemsRepo;

    private static final Instant MOC = Instant.now();

    private long deNhap() {
        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            return authorProblem.tao(new AuthorProblemUseCase.Command(
                    "xoa-" + System.nanoTime(), "Đề để xoá", "Nội dung", 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, (BigDecimal) null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false));
        }
    }

    private void xoaBoi(long problemId, long userId, String handle, Role vaiTro) {
        try (var phien = GiaLapDanhTinh.dongVai(userId, handle, vaiTro)) {
            assertThat(phien).isNotNull();
            authorProblem.xoa(problemId);
        }
    }

    private int demDe(long problemId) {
        return jdbc.sql("SELECT count(*) FROM problems WHERE id = :id")
                .param("id", problemId).query(Integer.class).single();
    }

    /**
     * Bài nộp dựng bằng SQL: ca này hỏi về XOÁ, không hỏi về đường nộp bài.
     *
     * <p>Phải chèn {@code source_blobs} trước — {@code submissions.source_sha256} là khoá
     * ngoại trỏ sang đó. Nội dung mã nguồn nằm ở bảng riêng, khử trùng lặp theo hash.
     */
    private void themBaiNop(long problemId) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (repeat('a', 64), 'int main(){}', 12)
                ON CONFLICT (sha256) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id,
                                         source_sha256, source_bytes, status)
                VALUES (:u, :p, 1, repeat('a', 64), 12, 'QUEUED')
                """).param("u", USER_ID).param("p", problemId).update();
    }

    private long kyThi(Instant batDau, Instant ketThuc) {
        return contests.tao(new ContestRepository.ContestMoi(
                "xoa-de-" + System.nanoTime(), "Thi thử", "ICPC",
                batDau, ketThuc, null, 20, true, true, ADMIN_ID));
    }

    // =========================================================================

    @Test
    @DisplayName("★ bản nháp chưa ai đụng tới thì xoá được")
    void ban_nhap_xoa_duoc() {
        long id = deNhap();

        xoaBoi(id, SETTER_ID, "setter", Role.SETTER);

        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("★ đề đã có bài nộp thì KHÔNG xoá được — bài nộp là lịch sử của người khác")
    void de_da_co_bai_nop_thi_khong_xoa_duoc() {
        long id = deNhap();
        themBaiNop(id);

        assertThatThrownBy(() -> xoaBoi(id, SETTER_ID, "setter", Role.SETTER))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "problem.da_co_bai_nop");

        assertThat(demDe(id)).isEqualTo(1);
    }

    /**
     * ★ Kỳ thi đã KẾT THÚC vẫn chặn — đây là chỗ dễ nới nhất và cũng là chỗ sai nhất.
     *
     * <p>Kỳ thi xong rồi thì đề mở ra ngoài (FR-CON-07), nên "xong rồi, xoá được thôi" nghe
     * rất hợp lý. Nhưng bảng xếp hạng của kỳ thi ấy vẫn còn, và nó vẫn mang nhãn của đề này.
     */
    @Test
    @DisplayName("★ đề thuộc kỳ thi ĐÃ KẾT THÚC vẫn không xoá được")
    void de_thuoc_ky_thi_da_ket_thuc_van_khong_xoa_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.minus(Duration.ofHours(4)), MOC.minus(Duration.ofHours(1)));
        contests.themDe(ky, id, "A", 1, 100);

        assertThatThrownBy(() -> xoaBoi(id, SETTER_ID, "setter", Role.SETTER))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("code", "problem.dang_thuoc_ky_thi");

        assertThat(demDe(id)).isEqualTo(1);
    }

    /**
     * ★ Chốt sở hữu, không phải chốt vai trò.
     *
     * <p>Người gọi ở đây <b>có</b> vai trò SETTER — nếu để vai trò USER thì
     * {@code @RequiresRole} chặn từ ngoài cửa và ca này không kiểm được gì về quyền sở hữu,
     * chỉ kiểm lại một thứ {@code AuthorizationIT} đã kiểm rồi.
     *
     * <p>Điều kiện chủ sở hữu nằm trong chính câu {@code DELETE}, đúng khuôn chống IDOR của
     * {@code oj-api/CLAUDE.md} mục 2. Kết quả là 404 chứ không 403: 403 xác nhận đề tồn
     * tại, mà một bản nháp thì ngay cả sự tồn tại cũng chưa công khai.
     */
    @Test
    @DisplayName("★ SETTER khác không xoá được đề của người ra đề này")
    void setter_khac_khong_xoa_duoc() {
        long id = deNhap();

        assertThatThrownBy(() -> xoaBoi(id, USER_ID, "dev", Role.SETTER))
                .isInstanceOf(ProblemNotFoundException.class);

        assertThat(demDe(id)).isEqualTo(1);
    }

    /**
     * ★ Lớp phòng thủ THỨ HAI, và ca này là ca duy nhất chạm được vào nó.
     *
     * <p>{@link #setter_khac_khong_xoa_duoc()} dừng ở {@code doc()} — chốt trong use-case —
     * nên nó KHÔNG chứng minh được gì về điều kiện {@code owner_id = :requesterId} trong câu
     * {@code DELETE}. Đo bằng đột biến: bỏ hẳn điều kiện ấy khỏi SQL mà cả bộ test vẫn xanh.
     *
     * <p>Một hàng rào không có ai canh là một hàng rào sẽ bị gỡ trong một lần dọn dẹp, và
     * ngày nó bị gỡ thì lỗ hổng chỉ cần một người gọi repository quên kiểm quyền phía trên.
     * {@code oj-api/CLAUDE.md} mục 2 đòi điều kiện chủ sở hữu nằm TRONG câu query đúng vì
     * thế — nên ca này gọi thẳng repository, bỏ qua use-case, để hàng rào ấy có người canh.
     */
    @Test
    @DisplayName("★ câu DELETE tự nó cũng từ chối người không sở hữu")
    void cau_delete_tu_no_cung_chan() {
        long id = deNhap();

        assertThat(problemsRepo.xoa(id, USER_ID, false))
                .describedAs("người không sở hữu, không phải admin → 0 dòng bị xoá")
                .isFalse();
        assertThat(demDe(id)).isEqualTo(1);

        assertThat(problemsRepo.xoa(id, SETTER_ID, false)).isTrue();
        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("ADMIN xoá được đề của người khác")
    void admin_xoa_duoc() {
        long id = deNhap();

        xoaBoi(id, ADMIN_ID, "admin", Role.ADMIN);

        assertThat(demDe(id)).isZero();
    }

    /** ★ Đường thoát trọn vẹn cho một đề gắn nhầm: gỡ khỏi kỳ thi, rồi xoá. */
    @Test
    @DisplayName("★ gỡ khỏi kỳ thi rồi thì xoá được — hết ngõ cụt")
    void go_khoi_ky_thi_roi_thi_xoa_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)));
        contests.themDe(ky, id, "A", 1, 100);

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            authorContest.goDeKhoiKyThi(ky, id);
        }

        assertThat(contests.deCua(ky)).isEmpty();
        xoaBoi(id, SETTER_ID, "setter", Role.SETTER);
        assertThat(demDe(id)).isZero();
    }

    @Test
    @DisplayName("kỳ thi đã bắt đầu thì không gỡ đề ra được")
    void ky_thi_da_bat_dau_thi_khong_go_duoc() {
        long id = deNhap();
        long ky = kyThi(MOC.minus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(2)));
        contests.themDe(ky, id, "A", 1, 100);

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThat(phien).isNotNull();
            assertThatThrownBy(() -> authorContest.goDeKhoiKyThi(ky, id))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("code", "contest.da_bat_dau");
        }

        assertThat(contests.deCua(ky)).hasSize(1);
    }
}
