package dev.oj.judging.application.usecase;

import dev.oj.platform.contest.ContestWindowQuery;

import java.util.List;
import java.util.OptionalLong;

final class LichThiGia implements ContestWindowQuery {

    Long contestDangChay;

    boolean biKhoa;

    boolean coKyThi;

    int soLanHoi;

    @Override
    public OptionalLong contestDangChayChuaDe(long problemId) {
        soLanHoi++;
        return contestDangChay == null ? OptionalLong.empty() : OptionalLong.of(contestDangChay);
    }

    @Override
    public boolean coKyThiDangChay() {
        return coKyThi;
    }

    @Override
    public boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe) {
        return biKhoa && !laNguoiRaDe;
    }

    List<Long> idBiKhoa = List.of();

    boolean thuocKyThiNaoDo;

    @Override
    public boolean deNamTrongKyThiNaoDo(long problemId) {
        return thuocKyThiNaoDo;
    }

    @Override
    public List<Long> deBiKhoaChoNguoiXem(Long userId) {
        return idBiKhoa;
    }
}
