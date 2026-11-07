package dev.oj.judging.domain;

import dev.oj.contract.Sha256;
import dev.oj.contract.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class SubmissionTest {

    private static final Instant T0 = Instant.parse("2026-03-01T10:00:00Z");
    private static final String SHA = Sha256.hexOf("int main(){}");

    private static Submission queued() {
        return new Submission(1L, 7L, 42L, null, 3, SHA, 12, T0,
                SubmissionStatus.QUEUED, DomainRules.ATTEMPT_NONE, 5,
                null, null, null, null);
    }

    private static JudgeOutcome wrongAnswer() {
        return new JudgeOutcome(Verdict.WA, 0, 100, 7, 230, 4096);
    }

    @Nested
    @DisplayName("attempt chỉ tăng, và chỉ tăng lúc claim")
    class AttemptChiTang {

        @Test
        void claim_tang_attempt_va_chuyen_sang_judging() {
            Submission judging = queued().markJudging(DomainRules.FIRST_ATTEMPT);

            assertThat(judging.status()).isEqualTo(SubmissionStatus.JUDGING);
            assertThat(judging.attempt()).isEqualTo(1);
            assertThat(judging.isPending()).isTrue();
        }

        @Test
        void claim_voi_attempt_khong_lon_hon_thi_bi_tu_choi() {
            Submission judging = queued().markJudging(2);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> judging.markQueued().markJudging(2))
                    .withMessageContaining("attempt chỉ tăng");
        }

        @Test
        void reaper_dua_ve_queued_ma_KHONG_tang_attempt() {
            Submission reclaimed = queued().markJudging(1).markQueued();

            assertThat(reclaimed.status()).isEqualTo(SubmissionStatus.QUEUED);
            assertThat(reclaimed.attempt()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("verdict chỉ được ghi cho bài đang thật sự được chấm")
    class GhiVerdict {

        @Test
        void ghi_verdict_tu_judging() {
            Submission done = queued().markJudging(1).markDone(wrongAnswer(), T0.plusSeconds(2));

            assertThat(done.status()).isEqualTo(SubmissionStatus.DONE);
            assertThat(done.outcome().verdict()).isEqualTo(Verdict.WA);
            assertThat(done.judgedAt()).isEqualTo(T0.plusSeconds(2));
            assertThat(done.isPending()).isFalse();
        }

        @Test
        void bai_chua_duoc_claim_thi_khong_ghi_verdict_duoc() {
            Submission queued = queued();

            assertThatIllegalStateException()
                    .isThrownBy(() -> queued.markDone(wrongAnswer(), T0))
                    .withMessageContaining("QUEUED -> DONE");
        }

        @Test
        void goi_hai_lan_thi_lan_hai_bi_tu_choi_va_verdict_cu_nguyen_ven() {
            Submission done = queued().markJudging(1).markDone(wrongAnswer(), T0.plusSeconds(2));
            JudgeOutcome accepted = new JudgeOutcome(Verdict.AC, 100, 100, null, 120, 2048);

            assertThatIllegalStateException()
                    .isThrownBy(() -> done.markDone(accepted, T0.plusSeconds(9)));
            assertThat(done.outcome().verdict()).isEqualTo(Verdict.WA);
        }

        @Test
        void markDone_tu_choi_outcome_null() {
            Submission judging = queued().markJudging(1);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> judging.markDone(null, T0))
                    .withMessageContaining("outcome");
        }
    }

    @Nested
    @DisplayName("rejudge giữ nguyên verdict cũ để UI hiện \"WA · đang chấm lại\"")
    class Rejudge {

        @Test
        void quay_lai_hang_doi_va_claim_lai_van_giu_outcome_cu() {
            Submission done = queued().markJudging(1).markDone(wrongAnswer(), T0.plusSeconds(2));

            Submission rejudging = done.markQueued().markJudging(2);

            assertThat(rejudging.status()).isEqualTo(SubmissionStatus.JUDGING);
            assertThat(rejudging.attempt()).isEqualTo(2);
            assertThat(rejudging.outcome().verdict()).isEqualTo(Verdict.WA);
            assertThat(rejudging.judgedAt()).isNotNull();
        }
    }

    @Nested
    class BatBienCauTruc {

        @Test
        void DONE_ma_khong_co_outcome_la_khong_dung_duoc() {
            assertThatIllegalStateException().isThrownBy(() -> new Submission(
                    1L, 7L, 42L, null, 3, SHA, 12, T0,
                    SubmissionStatus.DONE, 1, 5, null, null, null, null));
        }

        @Test
        void an_bai_thi_phai_biet_ai_an() {
            assertThatIllegalStateException().isThrownBy(() -> new Submission(
                    1L, 7L, 42L, null, 3, SHA, 12, T0,
                    SubmissionStatus.QUEUED, 0, 5, null, null, T0, null));

            assertThatCode(() -> new Submission(
                    1L, 7L, 42L, null, 3, SHA, 12, T0,
                    SubmissionStatus.QUEUED, 0, 5, null, null, T0, 99L))
                    .doesNotThrowAnyException();
        }

        @Test
        void isHidden_va_isInContest() {
            assertThat(queued().isHidden()).isFalse();
            assertThat(queued().isInContest()).isFalse();
        }

        @Test
        void sourceSha256_phai_dung_dang() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Submission(
                    1L, 7L, 42L, null, 3, "KHONG-PHAI-HEX", 12, T0,
                    SubmissionStatus.QUEUED, 0, 5, null, null, null, null));
        }
    }

    @Test
    void khong_ton_tai_phuong_thuc_xoa_bai_nop() {
        assertThat(Submission.class.getDeclaredMethods())
                .extracting(Method::getName)
                .noneMatch(name -> name.toLowerCase().contains("delete")
                        || name.toLowerCase().contains("remove"));
    }

    @Test
    void bang_chuyen_trang_thai() {
        assertThat(SubmissionStatus.QUEUED.canTransitionTo(SubmissionStatus.JUDGING)).isTrue();
        assertThat(SubmissionStatus.QUEUED.canTransitionTo(SubmissionStatus.DONE)).isFalse();
        assertThat(SubmissionStatus.JUDGING.canTransitionTo(SubmissionStatus.DONE)).isTrue();
        assertThat(SubmissionStatus.JUDGING.canTransitionTo(SubmissionStatus.QUEUED)).isTrue();
        assertThat(SubmissionStatus.DONE.canTransitionTo(SubmissionStatus.QUEUED)).isTrue();
        assertThat(SubmissionStatus.DONE.canTransitionTo(SubmissionStatus.DONE)).isFalse();
        assertThat(SubmissionStatus.fromCode("JUDGING")).isEqualTo(SubmissionStatus.JUDGING);
    }
}
