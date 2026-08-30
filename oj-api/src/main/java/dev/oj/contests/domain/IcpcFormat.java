package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;

/**
 * Thể thức ICPC — FR-CON-06. Một file, không sửa gì khác (NFR M4).
 *
 * <h2>Ba quy tắc, và cả ba đều có một cạnh sắc</h2>
 * <ol>
 *   <li><b>Một đề chỉ tính lần AC ĐẦU TIÊN.</b> Nộp lại sau khi đã đạt không đổi gì cả —
 *       không thêm điểm, và <b>không thêm penalty</b>. Đây là cạnh dễ làm sai nhất: nếu bài
 *       nộp sau khi AC vẫn cộng penalty thì người ta bị phạt vì kiểm tra lại bài của mình.</li>
 *   <li><b>Penalty = phút kể từ giờ bắt đầu tới lúc AC, cộng {@code penalty_minutes} cho mỗi
 *       lần sai TRƯỚC lần AC.</b> Lần sai <i>sau</i> khi AC không tính — hệ quả của quy tắc 1.</li>
 *   <li><b>Đề chưa đạt thì không đóng penalty nào.</b> Người nộp sai hai mươi lần rồi bỏ cuộc
 *       xếp ngang người chưa nộp bài nào — cố ý. Phạt việc <i>cố gắng</i> là dạy người ta
 *       đừng thử.</li>
 * </ol>
 *
 * <p>{@code score} của bài nộp bị bỏ qua hoàn toàn: ICPC chỉ biết đạt hay không đạt. Một đề
 * chấm theo subtask đặt trong contest ICPC vẫn chỉ tính khi đạt <b>toàn bộ</b>, và đó là điều
 * người ra đề phải biết trước — không phải điều thể thức tự đoán.
 */
public final class IcpcFormat implements ContestFormat {

    public static final String CODE = "ICPC";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public KetQuaDe apDung(KetQuaDe hienTai, BaiDaCham bai, Contest contest) {
        // Đã đạt rồi thì mọi bài nộp sau đó là không việc gì cả — xem quy tắc 1.
        if (hienTai.daDat()) {
            return new KetQuaDe(hienTai.problemId(), hienTai.diemCaoNhat(),
                    hienTai.soLanSaiTruocKhiDat(), hienTai.datLuc(), hienTai.baiDatId(),
                    Math.max(hienTai.baiCuoiDaTinh(), bai.submissionId()));
        }
        if (bai.laAc()) {
            return new KetQuaDe(hienTai.problemId(), 1, hienTai.soLanSaiTruocKhiDat(),
                    bai.nopLuc(), bai.submissionId(), bai.submissionId());
        }
        return new KetQuaDe(hienTai.problemId(), 0, hienTai.soLanSaiTruocKhiDat() + 1,
                null, null, bai.submissionId());
    }

    /**
     * Điểm ICPC <b>là</b> số đề đạt — không có khái niệm điểm từng phần.
     *
     * <p>{@code penaltyGiay} trả về 0 ở đây, và đó không phải thiếu sót: penalty cần giờ bắt
     * đầu và {@code penalty_minutes}, hai thứ mà chữ ký này không có. Xem
     * {@link #penaltyGiay(Collection, Contest)} ngay dưới.
     */
    @Override
    public TongKet tongHop(Collection<KetQuaDe> cacDe) {
        int soBaiDat = 0;
        Instant cuoi = null;

        for (KetQuaDe de : cacDe) {
            if (!de.daDat()) {
                continue;   // quy tắc 3 — chưa đạt thì không đóng penalty nào
            }
            soBaiDat++;
            cuoi = muonHon(cuoi, de.datLuc());
        }
        return new TongKet(soBaiDat, 0, soBaiDat, cuoi);
    }

    /**
     * Penalty = phút tới lúc AC, cộng {@code penalty_minutes} cho mỗi lần sai TRƯỚC lần AC.
     *
     * <p>Đề chưa đạt đóng góp 0 — quy tắc 3, xem javadoc của class.
     */
    @Override
    public int penaltyGiay(Collection<KetQuaDe> cacDe, Contest contest) {
        long tong = 0;
        for (KetQuaDe de : cacDe) {
            if (!de.daDat()) {
                continue;
            }
            tong += contest.keTuLucBatDau(de.datLuc()).toSeconds();
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
