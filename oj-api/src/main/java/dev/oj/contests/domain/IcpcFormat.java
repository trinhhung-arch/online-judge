package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Set;

public final class IcpcFormat implements ContestFormat {

    public static final String CODE = "ICPC";

    private static final Set<String> TINH_LA_LAN_SAI = Set.of("WA", "TLE", "MLE", "RE");

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public KetQuaDe apDung(KetQuaDe hienTai, BaiDaCham bai, Contest contest) {
        if (bai.submissionId() <= hienTai.baiCuoiDaTinh()) {
            return hienTai;
        }
        if (hienTai.daDat()) {
            return new KetQuaDe(hienTai.problemId(), hienTai.diemCaoNhat(),
                    hienTai.soLanSaiTruocKhiDat(), hienTai.datLuc(), hienTai.baiDatId(),
                    Math.max(hienTai.baiCuoiDaTinh(), bai.submissionId()));
        }
        if (bai.laAc()) {
            return new KetQuaDe(hienTai.problemId(), 1, hienTai.soLanSaiTruocKhiDat(),
                    bai.nopLuc(), bai.submissionId(), bai.submissionId());
        }
        int phatThem = TINH_LA_LAN_SAI.contains(bai.verdict()) ? 1 : 0;
        return new KetQuaDe(hienTai.problemId(), 0, hienTai.soLanSaiTruocKhiDat() + phatThem,
                null, null, bai.submissionId());
    }

    @Override
    public TongKet tongHop(Collection<KetQuaDe> cacDe) {
        int soBaiDat = 0;
        Instant cuoi = null;

        for (KetQuaDe de : cacDe) {
            if (!de.daDat()) {
                continue;
            }
            soBaiDat++;
            cuoi = muonHon(cuoi, de.datLuc());
        }
        return new TongKet(soBaiDat, 0, soBaiDat, cuoi);
    }

    @Override
    public int penaltyGiay(Collection<KetQuaDe> cacDe, Contest contest) {
        long tong = 0;
        for (KetQuaDe de : cacDe) {
            if (!de.daDat()) {
                continue;
            }
            tong += contest.keTuLucBatDau(de.datLuc()).toMinutes() * 60L;
            tong += (long) de.soLanSaiTruocKhiDat() * contest.penaltyMinutes() * 60L;
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, tong));
    }

    private static Instant muonHon(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Comparator.<Instant>naturalOrder().compare(a, b) >= 0 ? a : b;
    }
}
