package dev.oj.it;

import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link JobContext} giả, để chạy một {@code JobHandler} mà không cần {@code JobRunner}.
 *
 * <h2>Vì sao tách khỏi runner trong test</h2>
 * Hai thứ hỏng theo hai kiểu khác nhau và đáng được kiểm riêng: {@code JobRunner} lo
 * <b>vòng đời</b> (claim, lease, thu hồi job treo — kiểm ở {@code JobsIT} trên Postgres thật),
 * còn handler lo <b>việc</b>. Trộn chúng lại thì một ca đỏ không nói được cái nào hỏng.
 *
 * <p>Lớp này ghi lại tiến độ và vị trí đã lưu để test khẳng định được rằng handler <i>thật sự</i>
 * báo cáo — thứ mà hợp đồng của {@code JobHandler} đòi và một handler quên gọi vẫn chạy đúng.
 */
final class JobContextGia implements JobContext {

    private final Map<String, Object> params;
    private final Long nguoiTao;

    Map<String, Object> viTriDaLuu = new HashMap<>();
    final List<String> suKien = new ArrayList<>();
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
    }

    @Override
    public void kiemHuy() {
        if (huy) {
            throw JobsException.daBiHuy();
        }
    }
}
