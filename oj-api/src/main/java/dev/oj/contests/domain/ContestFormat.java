package dev.oj.contests.domain;

import java.time.Instant;
import java.util.Collection;

/**
 * ★ Thể thức tính điểm — FR-CON-06, và là chỗ NFR M4 <i>"thêm một thể thức = 1 file"</i> được
 * hiện thực hoá.
 *
 * <h2>Hai phương thức, và ranh giới giữa chúng là điều quan trọng nhất</h2>
 * <ul>
 *   <li>{@link #apDung} — một bài nộp làm đổi kết quả của <b>một đề</b> thế nào.</li>
 *   <li>{@link #tongHop} — kết quả từng đề cộng lại thành <b>một dòng bảng xếp hạng</b> thế nào.</li>
 * </ul>
 *
 * <p>Tách như vậy vì hai thể thức khác nhau ở <i>cả hai</i> chỗ, nhưng theo hai cách độc lập:
 * ICPC quan tâm lần AC đầu tiên và số lần sai trước đó; IOI quan tâm điểm cao nhất và không
 * quan tâm số lần. Một hàm gộp sẽ có một câu {@code if} về thể thức ở giữa, và thể thức thứ ba
 * sẽ thêm một nhánh nữa vào đúng chỗ đó.
 *
 * <h2>★ Cả hai phương thức phải THUẦN và IDEMPOTENT</h2>
 * {@code StandingsUpdater} chạy theo lô mỗi 2 giây và <b>sẽ</b> chạy lại cùng một bài nộp:
 * sau restart, sau một lần rebuild (FR-CON-08), sau khi lease của job hết hạn. Nếu
 * {@link #apDung} cộng dồn thay vì tính lại, một lần chạy lại là bảng xếp hạng sai — và sai
 * ở một kỳ thi thì không sửa được sau khi đã trao giải.
 *
 * <p>Chốt của tính idempotent nằm ở tầng trên ({@code last_applied_submission_id}), nhưng nó
 * chỉ đúng nếu hàm ở đây cũng thuần. Hai lớp, cả hai đều cần.
 *
 * <h2>Thêm một thể thức</h2>
 * Viết một file hiện thực interface này, đặt {@link #code()} khớp một giá trị mới trong
 * {@code CHECK (format IN (...))} của V7, và thêm một dòng vào
 * {@link ContestFormats#tuMa(String)}. Không sửa gì khác — không sửa
 * {@code StandingsUpdater}, không sửa repository, không sửa API.
 */
public interface ContestFormat {

    /** Khớp {@code contests.format}: {@code ICPC} hoặc {@code IOI}. */
    String code();

    /**
     * Kết quả mới của <b>một đề</b> sau khi tính thêm một bài nộp.
     *
     * @param hienTai kết quả trước đó của người này trên đề này
     * @param bai     một bài nộp đã chấm xong
     * @return kết quả mới. <b>Không</b> sửa {@code hienTai}
     */
    KetQuaDe apDung(KetQuaDe hienTai, BaiDaCham bai, Contest contest);

    /** Cộng kết quả từng đề thành một dòng bảng xếp hạng. */
    TongKet tongHop(Collection<KetQuaDe> cacDe);

    /**
     * Penalty, tính bằng giây — <b>mặc định là 0</b>.
     *
     * <p>Tách khỏi {@link #tongHop} vì penalty cần {@link Contest} (giờ bắt đầu và
     * {@code penalty_minutes}) còn {@code tongHop} thì không. Là phương thức <i>mặc định</i>
     * chứ không phải trừu tượng vì phần lớn thể thức không có khái niệm penalty: IOI không có,
     * và một thể thức thứ ba nhiều khả năng cũng không.
     *
     * <p>Nhờ vậy {@code StandingsUpdater} gọi thẳng, không phải một câu
     * {@code if (format instanceof IcpcFormat)} — thứ mà mỗi thể thức mới sẽ thêm một nhánh
     * vào đúng chỗ đó, và làm hỏng lời hứa "thêm một thể thức = 1 file".
     */
    default int penaltyGiay(Collection<KetQuaDe> cacDe, Contest contest) {
        return 0;
    }

    /**
     * Bài nộp đã chấm xong, rút gọn còn đúng thứ hai thể thức cần.
     *
     * <p>Cố ý không mang {@code verdict} dạng chuỗi mà mang {@link #laAc}: ICPC hỏi
     * <i>"có AC không"</i>, IOI hỏi <i>"được bao nhiêu điểm"</i>, và không thể thức nào cần
     * phân biệt WA với TLE. Một trường không có mặt thì không ai viết một câu {@code if} dựa
     * vào nó — và một bảng xếp hạng phân biệt WA với TLE là một bảng xếp hạng sắp có ngoại lệ.
     */
    record BaiDaCham(long submissionId, long userId, long problemId,
                     boolean laAc, int score, Instant nopLuc) {
    }

    /**
     * Kết quả của một người trên một đề — khớp {@code contest_problem_standings} của V7.
     *
     * @param soLanSaiTruocKhiDat chỉ ICPC dùng. IOI để 0
     * @param datLuc              {@code null} nếu chưa đạt
     */
    record KetQuaDe(long problemId, int diemCaoNhat, int soLanSaiTruocKhiDat,
                    Instant datLuc, Long baiDatId, long baiCuoiDaTinh) {

        public static KetQuaDe trong(long problemId) {
            return new KetQuaDe(problemId, 0, 0, null, null, 0L);
        }

        public boolean daDat() {
            return datLuc != null;
        }
    }

    /** Một dòng bảng xếp hạng — khớp {@code contest_standings}. */
    record TongKet(int tongDiem, int penaltyGiay, int soBaiDat, Instant lanGhiDiemCuoi) {

        public static final TongKet TRONG = new TongKet(0, 0, 0, null);
    }
}
