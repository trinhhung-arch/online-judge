package dev.oj.judging.application.published;

import java.time.Instant;

public interface QueueStatusQuery {

    TrangThai doc();

    record TrangThai(int dangCho, int dangCham, int rejudgeDangCho,
                     Instant choLauNhat, int mayChamSong) {

        public boolean rong() {
            return dangCho == 0 && dangCham == 0;
        }

        public long choLauNhatMs(Instant bayGio) {
            return choLauNhat == null ? 0
                    : Math.max(0, java.time.Duration.between(choLauNhat, bayGio).toMillis());
        }

        public long choUocTinhMs(double baiMoiGiay) {
            if (dangCho == 0 || baiMoiGiay <= 0) {
                return 0;
            }
            return Math.round(dangCho / baiMoiGiay * 1000);
        }
    }
}
