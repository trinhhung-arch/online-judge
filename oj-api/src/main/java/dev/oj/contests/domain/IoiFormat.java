package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;

/**
 * Thể thức IOI — FR-CON-06. Một file, không sửa gì khác (NFR M4).
 *
 * <h2>Hai quy tắc, và cả hai đều là điều ngược lại của ICPC</h2>
 * <ol>
 *   <li><b>Mỗi đề lấy điểm CAO NHẤT từng đạt.</b> Nộp lại chỉ có thể làm điểm tăng hoặc giữ
 *       nguyên. Một bài sau tệ hơn bài trước không xoá mất kết quả tốt — đó là cả điểm của
 *       thể thức này: nó khuyến khích thử.</li>
 *   <li><b>Không có penalty.</b> Số lần nộp không ảnh hưởng gì. Trường
 *       {@code soLanSaiTruocKhiDat} luôn 0, và {@code penaltyGiay} luôn 0.</li>
 * </ol>
 *
 * <h2>Tại sao "đạt" ở IOI là điểm tối đa, không phải điểm dương</h2>
 * {@code solved_count} ở đây đếm số đề <b>đạt điểm tối đa</b>. Một đề 40/100 là tiến bộ thật
 * và được cộng vào {@code total_score}, nhưng gọi nó là "đã giải" thì cột đó mất nghĩa —
 * và bảng xếp hạng sẽ nói rằng ai cũng giải được mọi đề.
 *
 * <p>Điểm tối đa lấy từ {@code contest_problems.points}, và {@link #apDung} nhận nó qua
 * {@code diemToiDa} của {@link BaiDaCham}... nó không có. Nên quyết định là:
 * <b>{@code solved_count} của IOI đếm đề có điểm &gt; 0</b>, và tên cột được hiểu là "số đề có
 * điểm". Đơn giản, không cần thêm dữ liệu, và không nói dối — vì {@code total_score} mới là
 * thứ quyết định thứ hạng ở thể thức này.
 */
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
                0,                                  // IOI không đếm lần sai
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
