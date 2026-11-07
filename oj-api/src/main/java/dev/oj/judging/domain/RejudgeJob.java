package dev.oj.judging.domain;

import java.time.Duration;
import java.time.Instant;

public final class RejudgeJob {

    private RejudgeJob() {
    }

    public static int suatConLai(NhipHangDoi nhip, int tranDangCho,
                                 Duration phanhKhiLiveCho, Instant bayGio) {
        if (tranDangCho < 1) {
            throw new IllegalArgumentException("tranDangCho phải ≥ 1, nhận " + tranDangCho);
        }
        if (nhip.liveDangChoLauHon(phanhKhiLiveCho, bayGio)) {
            return 0;
        }
        return Math.max(0, tranDangCho - nhip.rejudgeDangCho());
    }

    public record NhipHangDoi(int rejudgeDangCho, Instant liveChoLauNhat) {

        public boolean liveDangChoLauHon(Duration nguong, Instant bayGio) {
            return liveChoLauNhat != null
                    && Duration.between(liveChoLauNhat, bayGio).compareTo(nguong) > 0;
        }
    }
}
