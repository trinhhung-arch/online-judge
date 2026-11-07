package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;

public interface ContestFormat {

    String code();

    KetQuaDe apDung(KetQuaDe hienTai, BaiDaCham bai, Contest contest);

    TongKet tongHop(Collection<KetQuaDe> cacDe);

    default int penaltyGiay(Collection<KetQuaDe> cacDe, Contest contest) {
        return 0;
    }

    record BaiDaCham(long submissionId, long userId, long problemId,
                     String verdict, int score, int maxScore, int diemDe, Instant nopLuc) {

        public boolean laAc() {
            return "AC".equals(verdict);
        }
    }

    record KetQuaDe(long problemId, int diemCaoNhat, int soLanSaiTruocKhiDat,
                    Instant datLuc, Long baiDatId, long baiCuoiDaTinh) {

        public static KetQuaDe trong(long problemId) {
            return new KetQuaDe(problemId, 0, 0, null, null, 0L);
        }

        public boolean daDat() {
            return datLuc != null;
        }
    }

    record TongKet(int tongDiem, int penaltyGiay, int soBaiDat, Instant lanGhiDiemCuoi) {

        public static final TongKet TRONG = new TongKet(0, 0, 0, null);
    }
}
