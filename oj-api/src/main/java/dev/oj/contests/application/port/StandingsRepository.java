package dev.oj.contests.application.port;

import dev.oj.contests.domain.ContestFormat.KetQuaDe;
import dev.oj.contests.domain.ContestFormat.TongKet;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Cổng <b>GHI</b> bảng xếp hạng — {@code contest_standings},
 * {@code contest_problem_standings} và hai bảng chụp đóng băng (V7).
 *
 * <h2>Đường đọc nằm ở {@link StandingsReader}, và tách là cố ý</h2>
 * Hai đường có hai nhịp và hai người dùng hoàn toàn khác nhau: đây là {@code StandingsUpdater}
 * chạy hai giây một lần trong nền; bên kia là hàng trăm người cùng mở trang bảng xếp hạng.
 * Bước 5.7 của {@code build-order.md} cũng gọi tên chúng riêng ra ({@code PostgresStandingsReader}).
 *
 * <p>Hệ quả thực dụng: một use-case chỉ đọc bảng xếp hạng <b>không tiêm được</b> phương thức
 * {@code xoaBangXepHang}. Đó là cùng một lập luận đã tách {@code ProblemAuthoringRepository}
 * khỏi {@code ProblemRepository} ở M4.
 *
 * <h2>★ Bất biến của mốc này: Redis là cache, ĐÂY là sự thật</h2>
 * Mọi giá trị trong Redis phải dựng lại được 100% từ các bảng sau interface này. Thêm một cột
 * vào bảng xếp hạng mà quên dạy {@code RebuildStandingsJob} dựng lại cột đó là <b>một lỗi im
 * lặng cho tới ngày Redis chết</b> — và ngày đó là ngày tệ nhất để phát hiện ra.
 */
public interface StandingsRepository {

    /**
     * ★ Watermark của một kỳ thi: {@code id} bài nộp lớn nhất đã được tính vào bảng.
     *
     * <h2>Vì sao {@code MAX} trên cột theo từng người lại là watermark đúng của cả kỳ thi</h2>
     * {@code last_applied_submission_id} lưu theo <i>từng người</i>, nhưng
     * {@code StandingsUpdater} xử lý bài nộp <b>theo đúng thứ tự id tăng dần</b>. Nên khi bài
     * số N đã được xử lý, mọi bài id nhỏ hơn N cũng đã được xử lý — bất kể của ai. Vậy
     * {@code MAX} qua mọi người chính là ranh giới đã xử lý của cả kỳ thi.
     *
     * <p>Điều kiện để lập luận này đúng là <b>thứ tự id</b>. Nếu {@code JudgingQueries} trả về
     * không đúng thứ tự, watermark nhảy qua một bài chưa xử lý và bài đó vĩnh viễn không vào
     * bảng — một lỗi im lặng, chỉ job đối soát drift (FR-CON-09) tìm ra.
     *
     * @return 0 nếu kỳ thi chưa có dòng nào
     */
    long watermark(long contestId);

    /**
     * Kết quả từng đề của một nhóm người, để tính lại tổng.
     *
     * <p>Trả về <b>mọi</b> đề của những người ấy, không chỉ đề vừa có bài nộp: tổng điểm và
     * penalty là hàm của <i>tất cả</i> các đề, nên tính lại mà thiếu một đề là ra một con số
     * sai một cách im lặng.
     */
    Map<Long, List<KetQuaDe>> ketQuaTheoNguoi(long contestId, Collection<Long> userIds);

    void ghiKetQuaDe(long contestId, long userId, KetQuaDe ketQua);

    void ghiTongKet(long contestId, long userId, TongKet tong, int penaltyGiay,
                    long baiCuoiDaTinh);

    /** Xoá sạch bảng xếp hạng của một kỳ thi — {@code RebuildStandingsJob} dựng lại từ đầu. */
    void xoaBangXepHang(long contestId);

    // -------------------------------------------------------------------------
    // Đóng băng — FR-CON-05
    // -------------------------------------------------------------------------

    boolean daChupDongBang(long contestId);

    /**
     * Chụp bảng xếp hạng tại thời điểm đóng băng.
     *
     * <p>Một câu {@code INSERT ... SELECT}, không kéo dữ liệu về Java. Chi phí O(số thí sinh),
     * đúng <b>một lần</b> — rẻ hơn nhiều so với lọc theo thời gian trên {@code submissions} ở
     * mỗi lần render bảng, thứ sẽ phải làm ở mỗi request trong suốt phần cuối kỳ thi.
     */
    void chupDongBang(long contestId, Instant freezeAt);

    /**
     * Toàn bộ tổng kết đã lưu của một kỳ thi — <b>chỉ dùng bởi job đối soát</b> (FR-CON-09).
     *
     * <h2>Vì sao phương thức này được phép tải cả bảng, còn {@link StandingsReader} thì không</h2>
     * Luật <i>"không bao giờ tải toàn bộ bảng"</i> ({@code oj-api/CLAUDE.md} mục 6) nói về
     * <b>request của người dùng</b>: hàng trăm người cùng mở trang bảng xếp hạng, và mỗi lần
     * tải cả bảng là nhân lên hàng trăm lần.
     *
     * <p>Job đối soát chạy <b>một lần mỗi vài giờ, trong nền</b>, và công việc của nó theo
     * định nghĩa là so <i>mọi</i> dòng — kiểm một nửa bảng thì không phát hiện được sai lệch ở
     * nửa kia. Cùng một hình dạng truy vấn, hai bối cảnh khác nhau, hai câu trả lời khác nhau.
     *
     * <p>Đặt ở port GHI chứ không ở port ĐỌC là cách nói điều đó bằng kiểu dữ liệu: use-case
     * phục vụ người dùng chỉ tiêm {@link StandingsReader}, nên nó <b>không gọi được</b> hàm này.
     */
    Map<Long, DiemDaLuu> tongKetDaLuu(long contestId);

    /** Hình chiếu tối thiểu để so sánh — không mang handle, không mang thời gian. */
    record DiemDaLuu(int tongDiem, int penaltyGiay, int soBaiDat) {
    }

    /** FR-CON-09 — ghi kết quả đối soát vào {@code standings_drift_checks}. */
    void ghiDrift(long contestId, int soDongKiem, int soDongLech, Map<String, Object> chiTiet);

}
