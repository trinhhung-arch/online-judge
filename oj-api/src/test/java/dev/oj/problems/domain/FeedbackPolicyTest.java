package dev.oj.problems.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** ★ FR-PROB-07 — bộ lọc quyết định tác giả bài nộp được thấy gì. */
class FeedbackPolicyTest {

    /**
     * ★ Thể thức ICPC. Biết mình sai là đủ; biết sai ở test nào là một kênh thông tin — nộp
     * mười bài cố tình khác nhau rồi đọc số thứ tự test sai là dò được hình dạng bộ test.
     */
    @Test
    @DisplayName("★ NONE — số thứ tự test sai bị giấu, kể cả khi bài nộp có lưu nó")
    void none_giau_so_thu_tu_test() {
        assertThat(FeedbackPolicy.of(FeedbackLevel.NONE).failedTestOrdinal(7)).isNull();
    }

    @Test
    @DisplayName("TEST_INDEX và SAMPLE_DETAIL đều cho xem số thứ tự")
    void hai_muc_con_lai_cho_xem() {
        assertThat(FeedbackPolicy.of(FeedbackLevel.TEST_INDEX).failedTestOrdinal(7)).isEqualTo(7);
        assertThat(FeedbackPolicy.of(FeedbackLevel.SAMPLE_DETAIL).failedTestOrdinal(7)).isEqualTo(7);
    }

    /** Bài AC không có test sai — {@code null} vào thì {@code null} ra ở mọi mức. */
    @Test
    void khong_co_test_sai_thi_moi_muc_deu_tra_null() {
        for (FeedbackLevel level : FeedbackLevel.values()) {
            assertThat(FeedbackPolicy.of(level).failedTestOrdinal(null))
                    .as("mức %s", level)
                    .isNull();
        }
    }

    /**
     * Chỉ {@code SAMPLE_DETAIL} mở nội dung test <b>mẫu</b> — thứ vốn đã in trong đề bài.
     * Không mức nào mở nội dung test ẩn; chuyện đó không đi qua class này (bất biến #1).
     */
    @Test
    void chi_SAMPLE_DETAIL_mo_noi_dung_test_mau() {
        assertThat(FeedbackPolicy.of(FeedbackLevel.SAMPLE_DETAIL).revealsSampleDetail()).isTrue();
        assertThat(FeedbackPolicy.of(FeedbackLevel.TEST_INDEX).revealsSampleDetail()).isFalse();
        assertThat(FeedbackPolicy.of(FeedbackLevel.NONE).revealsSampleDetail()).isFalse();
    }

    /**
     * Một bộ lọc mặc-định-ngầm là một bộ lọc sẽ mặc định sai về phía cho xem. Thà hỏng lúc
     * dựng còn hơn im lặng chọn mức dễ dãi nhất.
     */
    @Test
    @DisplayName("không có mặc định ngầm cho một bộ lọc")
    void khong_cho_null() {
        assertThatNullPointerException().isThrownBy(() -> FeedbackPolicy.of(null));
    }
}
