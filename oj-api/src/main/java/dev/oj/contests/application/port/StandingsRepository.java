package dev.oj.contests.application.port;

import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestFormat.TongKet;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface StandingsRepository {

    long watermark(long contestId);

    Map<Long, List<KetQuaDe>> ketQuaTheoNguoi(long contestId, Collection<Long> userIds);

    void ghiKetQuaDe(long contestId, long userId, KetQuaDe ketQua);

    void ghiTongKet(long contestId, long userId, TongKet tong, int penaltyGiay,
                    long baiCuoiDaTinh);

    void xoaBangXepHang(long contestId);

    boolean daChupDongBang(long contestId);

    void ghiTongKetDongBang(long contestId, long userId, TongKet tong, int penaltyGiay);

    void ghiKetQuaDeDongBang(long contestId, long userId, KetQuaDe ketQua);

    void chotBanChup(long contestId, Instant luc);

    Map<Long, DiemDaLuu> tongKetDaLuu(long contestId);

    record DiemDaLuu(int tongDiem, int penaltyGiay, int soBaiDat) {
    }

    void ghiDrift(long contestId, int soDongKiem, int soDongLech, Map<String, Object> chiTiet);

    List<Long> canSoatLech(Instant bayGio, Instant ketThucSau, Instant chuaSoatTu);

}
