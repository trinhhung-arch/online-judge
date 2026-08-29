package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Hai đường đọc: xem một bài (chống IDOR) và xem lịch sử của mình (bất biến #8). */
class ReadSubmissionUseCasesTest {

    private JudgingFakes fakes;
    private GetSubmissionUseCase get;
    private ListMySubmissionsUseCase list;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        get = new GetSubmissionUseCase(JudgingFakes.userIs(7L, Role.USER), fakes.submissions);
        list = new ListMySubmissionsUseCase(JudgingFakes.userIs(7L, Role.USER),
                fakes.submissions, JudgingFakes.properties());
    }

    /**
     * ★ Chống IDOR: danh tính người gọi đi <b>vào câu query</b>, không phải vào một câu
     * {@code if} chạy sau khi đã load.
     */
    @Test
    void danh_tinh_nguoi_goi_duoc_truyen_thang_vao_cau_query() {
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.DONE, 1);

        get.byId(101L);

        assertThat(fakes.submissions.requesterId).isEqualTo(7L);
        assertThat(fakes.submissions.requesterRole).isEqualTo(Role.USER);
    }

    /**
     * Query rỗng → 404 với <b>đúng câu chữ như khi bài không tồn tại</b>. Trả 403 là xác nhận
     * "id này có thật" — đủ để dò ra ai đã nộp bài nào.
     */
    @Test
    void bai_cua_nguoi_khac_va_bai_khong_ton_tai_cho_cung_mot_cau_tra_loi() {
        fakes.submissions.found = null;

        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> get.byId(999L))
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.NOT_FOUND);
                    assertThat(e.publicMessage()).isEqualTo("Không tìm thấy bài nộp này.");
                    assertThat(e.publicMessage()).doesNotContain("999");
                });
    }

    /** Xin 1000 thì nhận trần 50, <b>không nhận lỗi</b> ({@code oj-api/CLAUDE.md} mục 3). */
    @Test
    void xin_1000_ban_ghi_thi_nhan_tran_50() {
        list.list(null, 1000, SubmissionFilter.none());

        assertThat(fakes.submissions.sizeSeen).isEqualTo(50);
    }

    @Test
    void khong_truyen_size_thi_dung_mac_dinh_20() {
        list.list(null, null, null);

        assertThat(fakes.submissions.sizeSeen).isEqualTo(20);
        assertThat(fakes.submissions.cursorSeen).isNull();
    }

    @Test
    void cursor_hop_le_duoc_chuyen_thanh_so() {
        list.list("  500  ", 10, SubmissionFilter.none());

        assertThat(fakes.submissions.cursorSeen).isEqualTo(500L);
    }

    /** Cursor hỏng thì từ chối, không im lặng quay về trang đầu. */
    @Test
    void cursor_hong_thi_tu_choi() {
        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> list.list("hong", 10, SubmissionFilter.none()))
                .satisfies(e -> assertThat(e.code()).isEqualTo("submission.invalid_cursor"));

        assertThat(fakes.calls).doesNotContain("submissions.listForUser");
    }

    /**
     * ★ Bước 3.11 · FR-PROB-07 — bộ lọc phải nằm TRONG use-case.
     *
     * <p>Trước bước này, {@code FeedbackLevel.revealsFailedTestOrdinal()} không được gọi ở
     * bất kỳ đâu trong {@code src/main}: bộ lọc tồn tại như một hàm, và trang chi tiết đơn
     * giản là không trả con số nào. Đúng ở M1 (thà thiếu còn hơn lộ), nhưng nó có nghĩa là
     * FR-SUB-06 và FR-PROB-07 chưa hề được nối. Test này là chỗ giữ nó đã nối.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("★ đề mức NONE thì trang chi tiết KHÔNG trả số thứ tự test sai")
    void feedback_level_NONE_thi_giau_test_sai() {
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.DONE, 1);
        fakes.submissions.feedbackLevel = dev.oj.problems.domain.FeedbackLevel.NONE;

        var visible = get.detailById(101L);

        assertThat(visible.submission().outcome().failedTestOrdinal())
                .as("bài nộp VẪN lưu con số — nó cần cho SETTER và cho việc đối chiếu")
                .isNotNull();
        assertThat(visible.failedTestOrdinal())
                .as("nhưng thứ đi ra ngoài thì không")
                .isNull();
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("đề mức TEST_INDEX thì trả số thứ tự, và luôn trả log compiler")
    void feedback_level_TEST_INDEX_thi_cho_xem() {
        fakes.submissions.found = JudgingFakes.submission(101L, SubmissionStatus.DONE, 1);
        fakes.submissions.feedbackLevel = dev.oj.problems.domain.FeedbackLevel.TEST_INDEX;

        var visible = get.detailById(101L);

        assertThat(visible.failedTestOrdinal()).isNotNull();
        assertThat(visible.compileLog())
                .as("FR-SUB-06 — log compiler là output từ mã của chính người nộp, "
                        + "mọi mức feedback_level đều cho xem (ma trận hiển thị)")
                .isNotNull();
    }
}
