package dev.oj.platform.jobs;

import java.util.Map;

public interface JobContext {

    long jobId();

    Map<String, Object> params();

    Long nguoiTao();

    Map<String, Object> viTriDaLuu();

    void tienDo(int daXong, Integer tong);

    void luuViTri(Map<String, Object> viTri);

    void ghiSuKien(String muc, String thongDiep);

    void kiemHuy();
}
