package dev.oj.judging.domain;

import dev.oj.judging.domain.RejudgeJob.NhipHangDoi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RejudgeJobTest {

    private static final Instant BAY_GIO = Instant.parse("2026-08-30T10:00:00Z");
    private static final Duration PHANH = Duration.ofSeconds(5);
    private static final int TRAN = 2;

    @Nested
    @DisplayName("Trần 30% năng lực")
    class Tran {

        @Test
        @DisplayName("hàng đợi rejudge rỗng thì được đẩy đủ trần")
        void rong_thi_day_du_tran() {
            assertThat(suat(new NhipHangDoi(0, null))).isEqualTo(TRAN);
        }

        @Test
        @DisplayName("đã có 1 bài chờ thì chỉ còn 1 suất")
        void da_co_thi_tru_di() {
            assertThat(suat(new NhipHangDoi(1, null))).isEqualTo(1);
        }

        @Test
        @DisplayName("đã đủ trần thì 0 — và 0 nghĩa là CHƯA PHẢI LÚC, không phải ĐÃ XONG")
        void du_tran_thi_khong_day_them() {
            assertThat(suat(new NhipHangDoi(TRAN, null))).isZero();
        }

        @Test
        @DisplayName("★ vượt trần thì vẫn 0, không âm — một số âm sẽ thành LIMIT âm trong SQL")
        void vuot_tran_thi_khong_am() {
            assertThat(suat(new NhipHangDoi(TRAN + 5, null))).isZero();
        }

        @Test
        @DisplayName("trần dưới 1 là lỗi cấu hình, phải nổ chứ không im lặng dừng rejudge")
        void tran_khong_hop_le_thi_nem() {
            assertThatIllegalArgumentException().isThrownBy(
                    () -> RejudgeJob.suatConLai(new NhipHangDoi(0, null), 0, PHANH, BAY_GIO));
        }
    }

    @Nested
    @DisplayName("★ Phanh theo thời gian chờ của bài LIVE")
    class Phanh {

        @Test
        @DisplayName("live chờ quá ngưỡng thì về 0, dù hàng đợi rejudge đang rỗng")
        void live_cho_lau_thi_dung_han() {
            NhipHangDoi nhip = new NhipHangDoi(0, BAY_GIO.minusSeconds(6));

            assertThat(suat(nhip)).isZero();
        }

        @Test
        @DisplayName("live chờ dưới ngưỡng thì chạy bình thường")
        void live_cho_it_thi_van_chay() {
            NhipHangDoi nhip = new NhipHangDoi(0, BAY_GIO.minusSeconds(4));

            assertThat(suat(nhip)).isEqualTo(TRAN);
        }

        @Test
        @DisplayName("đúng bằng ngưỡng thì CHƯA phanh — so sánh là > chứ không >=")
        void dung_nguong_thi_chua_phanh() {
            NhipHangDoi nhip = new NhipHangDoi(0, BAY_GIO.minusSeconds(5));

            assertThat(suat(nhip)).isEqualTo(TRAN);
        }

        @Test
        @DisplayName("★ không có bài live nào chờ thì KHÔNG phanh, kể cả khi rejudge chờ rất lâu")
        void chi_dem_bai_live() {
            NhipHangDoi chiCoRejudgeCho = new NhipHangDoi(1, null);

            assertThat(suat(chiCoRejudgeCho)).isEqualTo(TRAN - 1);
        }
    }

    private static int suat(NhipHangDoi nhip) {
        return RejudgeJob.suatConLai(nhip, TRAN, PHANH, BAY_GIO);
    }
}
