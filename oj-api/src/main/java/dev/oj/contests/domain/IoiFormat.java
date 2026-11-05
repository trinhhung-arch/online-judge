package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;

public final class IoiFormat implements ContestFormat {

    public static final String CODE = "IOI";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public KetQuaDe apDung(KetQuaDe hienTai, BaiDaCham bai, Contest contest) {
        boolean totHon = bai.score() > hienTai.diemCaoNhat();
        return new KetQuaDe(
                hienTai.problemId(),
                Math.max(hienTai.diemCaoNhat(), bai.score()),
                0,
                totHon ? bai.nopLuc() : hienTai.datLuc(),
                totHon ? bai.submissionId() : hienTai.baiDatId(),
                Math.max(hienTai.baiCuoiDaTinh(), bai.submissionId()));
    }

    @Override
    public TongKet tongHop(Collection<KetQuaDe> cacDe) {
        int tongDiem = 0;
        int soDeCoDiem = 0;
        Instant cuoi = null;

        for (KetQuaDe de : cacDe) {
            tongDiem += de.diemCaoNhat();
            if (de.diemCaoNhat() > 0) {
                soDeCoDiem++;
                if (de.datLuc() != null && (cuoi == null || de.datLuc().isAfter(cuoi))) {
                    cuoi = de.datLuc();
                }
            }
        }
        return new TongKet(tongDiem, 0, soDeCoDiem, cuoi);
    }
}
