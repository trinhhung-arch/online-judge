package dev.oj.judging.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Hàng đợi bền: lease, reaper, retry IE. Đây là những con số quyết định R1 ("0 bài mất") và
 * R3 (tỉ lệ IE), nên chúng được kiểm ở tầng rẻ nhất trước khi lên tới chaos test.
 */
class JudgeQueueEntryTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private static JudgeQueueEntry waiting() {
        return new JudgeQueueEntry(1L, DomainRules.PRIORITY_LIVE, DomainRules.ATTEMPT_NONE,
                NOW, null, null, null, 0);
    }

    private static JudgeQueueEntry claimedUntil(Instant leaseUntil) {
        return new JudgeQueueEntry(1L, DomainRules.PRIORITY_LIVE, 1,
                NOW, NOW, 3, leaseUntil, 0);
    }

    @Test
    void hang_dang_cho_thi_khong_bao_gio_qua_han() {
        assertThat(waiting().isWaiting()).isTrue();
        assertThat(waiting().isClaimed()).isFalse();
        assertThat(waiting().isLeaseExpired(NOW.plusSeconds(9999))).isFalse();
    }

    /**
     * Vị từ của reaper trong SQL là {@code lease_until < now()}. Đúng bằng {@code now()} thì
     * <b>chưa</b> hết hạn — lệch một dấu bằng ở đây là domain và SQL trả lời khác nhau về
     * cùng một hàng.
     */
    @Test
    void het_han_dung_bang_bien_gioi_cua_cau_SQL() {
        JudgeQueueEntry entry = claimedUntil(NOW.plusSeconds(120));

        assertThat(entry.isLeaseExpired(NOW.plusSeconds(119))).isFalse();
        assertThat(entry.isLeaseExpired(NOW.plusSeconds(120))).isFalse();   // < , không phải <=
        assertThat(entry.isLeaseExpired(NOW.plusSeconds(121))).isTrue();
    }

    @Test
    void attempt_ke_tiep_la_so_worker_phai_gui_lai_nguyen_ven() {
        assertThat(waiting().nextAttempt()).isEqualTo(DomainRules.FIRST_ATTEMPT);
        assertThat(claimedUntil(NOW).nextAttempt()).isEqualTo(2);
    }

    /** FR-SUB-12 — chấm lại tối đa 2 lần vì IE, rồi mới báo lỗi cho người dùng. */
    @Test
    void con_luot_retry_IE_hay_khong() {
        int maxRetries = 2;

        assertThat(waiting().canRetryIe(maxRetries)).isTrue();
        assertThat(withIeRetries(1).canRetryIe(maxRetries)).isTrue();
        assertThat(withIeRetries(2).canRetryIe(maxRetries)).isFalse();
    }

    @Test
    void uu_tien_live_di_truoc_rejudge() {
        assertThat(waiting().isLive()).isTrue();
        assertThat(new JudgeQueueEntry(1L, DomainRules.PRIORITY_REJUDGE, 0, NOW, null, null, null, 0)
                .isLive()).isFalse();
        assertThat(DomainRules.PRIORITY_LIVE).isLessThan(DomainRules.PRIORITY_REJUDGE);
    }

    /**
     * "Đã giao mà không có hạn" là một bài reaper không bao giờ nhặt được: nó rơi khỏi cả hai
     * index partial và nằm đó mãi mãi — đúng loại lỗi mà R1 nói không được phép xảy ra.
     */
    @Test
    void da_giao_thi_bat_buoc_co_han() {
        assertThatIllegalStateException().isThrownBy(() ->
                new JudgeQueueEntry(1L, 0, 1, NOW, NOW, 3, null, 0));
        assertThatIllegalStateException().isThrownBy(() ->
                new JudgeQueueEntry(1L, 0, 1, NOW, null, null, NOW.plusSeconds(120), 0));
        assertThatIllegalStateException().isThrownBy(() ->
                new JudgeQueueEntry(1L, 0, 1, NOW, null, 3, null, 0));
    }

    private static JudgeQueueEntry withIeRetries(int count) {
        return new JudgeQueueEntry(1L, DomainRules.PRIORITY_LIVE, 1, NOW, null, null, null, count);
    }
}
