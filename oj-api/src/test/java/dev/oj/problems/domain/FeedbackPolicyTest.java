package dev.oj.problems.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class FeedbackPolicyTest {

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

    @Test
    void khong_co_test_sai_thi_moi_muc_deu_tra_null() {
        for (FeedbackLevel level : FeedbackLevel.values()) {
            assertThat(FeedbackPolicy.of(level).failedTestOrdinal(null))
                    .as("mức %s", level)
                    .isNull();
        }
    }

    @Test
    void chi_SAMPLE_DETAIL_mo_noi_dung_test_mau() {
        assertThat(FeedbackPolicy.of(FeedbackLevel.SAMPLE_DETAIL).revealsSampleDetail()).isTrue();
        assertThat(FeedbackPolicy.of(FeedbackLevel.TEST_INDEX).revealsSampleDetail()).isFalse();
        assertThat(FeedbackPolicy.of(FeedbackLevel.NONE).revealsSampleDetail()).isFalse();
    }

    @Test
    @DisplayName("không có mặc định ngầm cho một bộ lọc")
    void khong_cho_null() {
        assertThatNullPointerException().isThrownBy(() -> FeedbackPolicy.of(null));
    }
}
