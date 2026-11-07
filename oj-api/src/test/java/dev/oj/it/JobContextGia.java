package dev.oj.it;

import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JobContextGia implements JobContext {

    private final Map<String, Object> params;
    private final Long nguoiTao;

    Map<String, Object> viTriDaLuu = new HashMap<>();
    final List<String> suKien = new ArrayList<>();
    final List<String> mucSuKien = new ArrayList<>();
    int daXongCuoi;
    Integer tongCuoi;
    boolean huy;

    JobContextGia(Map<String, Object> params, Long nguoiTao) {
        this.params = params;
        this.nguoiTao = nguoiTao;
    }

    @Override
    public long jobId() {
        return 1L;
    }

    @Override
    public Map<String, Object> params() {
        return params;
    }

    @Override
    public Long nguoiTao() {
        return nguoiTao;
    }

    @Override
    public Map<String, Object> viTriDaLuu() {
        return viTriDaLuu;
    }

    @Override
    public void tienDo(int daXong, Integer tong) {
        daXongCuoi = daXong;
        tongCuoi = tong;
    }

    @Override
    public void luuViTri(Map<String, Object> viTri) {
        viTriDaLuu = new HashMap<>(viTri);
    }

    @Override
    public void ghiSuKien(String muc, String thongDiep) {
        suKien.add(thongDiep);
        mucSuKien.add(muc);
    }

    @Override
    public void kiemHuy() {
        if (huy) {
            throw JobsException.daBiHuy();
        }
    }
}
