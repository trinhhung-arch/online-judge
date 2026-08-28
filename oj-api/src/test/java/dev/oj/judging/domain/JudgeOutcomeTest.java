package dev.oj.judging.domain;

import dev.oj.contract.Verdict;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/** Kết quả một lần chấm: cái gì là mâu thuẫn (ném), cái gì chỉ là vô nghĩa (chuẩn hoá). */
class JudgeOutcomeTest {

    @Test
    void diem_khong_the_vuot_diem_toi_da() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JudgeOutcome(Verdict.AC, 101, 100, null, 10, 10));
    }

    @Test
    void markDone_tu_choi_verdict_null() {
        assertThatNullPointerException()
                .isThrownBy(() -> new JudgeOutcome(null, 0, 100, 1, 10, 10));
    }

    /** AC mà vẫn chỉ ra một test sai là mâu thuẫn nội tại — thường là bug gộp kết quả ở worker. */
    @Test
    void AC_ma_van_co_test_sai_la_mau_thuan() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JudgeOutcome(Verdict.AC, 100, 100, 7, 10, 10))
                .withMessageContaining("failedTestOrdinal");
    }

    @Test
    void so_thu_tu_test_phai_nam_trong_khoang_cua_de() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JudgeOutcome(Verdict.WA, 0, 100, 0, 10, 10));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JudgeOutcome(Verdict.WA, 0, 100,
                        DomainRules.MAX_TEST_ORDINAL + 1, 10, 10));
    }

    /**
     * ★ CE và IE thì <b>chuẩn hoá, không ném</b>: chưa test nào chạy nên hai con số đo vô
     * nghĩa. Ném lỗi ở đây sẽ chặn đúng đường ghi verdict, và một verdict không ghi được là
     * một bài quay lại hàng đợi mãi mãi.
     */
    @Test
    void CE_va_IE_bo_hai_con_so_do_thay_vi_tu_choi_ket_qua() {
        JudgeOutcome ce = new JudgeOutcome(Verdict.CE, 0, 100, null, 1234, 99999);
        JudgeOutcome ie = new JudgeOutcome(Verdict.IE, 0, 0, null, 1234, 99999);

        assertThat(ce.timeMs()).isNull();
        assertThat(ce.memoryKb()).isNull();
        assertThat(ie.timeMs()).isNull();
        assertThat(ie.isSystemFailure()).isTrue();
    }

    @Test
    void TLE_giu_nguyen_so_do_that_de_tang_api_con_lam_tron_10ms() {
        JudgeOutcome tle = new JudgeOutcome(Verdict.TLE, 0, 100, 3, 2034, 65536);

        assertThat(tle.timeMs()).isEqualTo(2034);   // làm tròn là việc của api (FR-SUB-11)
        assertThat(tle.isAccepted()).isFalse();
        assertThat(tle.isSystemFailure()).isFalse();
    }
}
