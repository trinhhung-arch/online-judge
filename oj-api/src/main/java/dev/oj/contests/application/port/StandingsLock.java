package dev.oj.contests.application.port;

/**
 * ★ Khoá ghi bảng xếp hạng của <b>một</b> kỳ thi — FR-CON-04, FR-CON-08.
 *
 * <h2>Vì sao cần khoá dù mỗi lô đã nằm trong một transaction</h2>
 * Transaction làm cho <i>một</i> lô nguyên tử; nó không làm cho <i>hai</i> lô tuần tự. Hai lượt
 * {@code StandingsUpdater.capNhat} cùng kỳ thi — nhịp 2 giây và {@code RebuildStandingsJob},
 * hoặc hai instance API — từng đọc chung một watermark rồi cùng ghi. Lượt đến sau áp lại cả lô
 * lên trạng thái lượt trước vừa ghi: với ICPC, một lần sai thành hai lần sai, tức thêm
 * {@code penalty_minutes} cho một bài thí sinh chỉ nộp một lần. Và một lượt xoá của job dựng
 * lại chen vào giữa một lô làm watermark nhảy qua những bài vừa bị xoá — chúng biến khỏi bảng.
 * Cả hai đã tái hiện được trên Postgres thật: {@code BangXepHangGhiSongSongIT}.
 *
 * <h2>Hai luật cho người gọi — sai luật nào thì khoá cũng như không</h2>
 * <ol>
 *   <li><b>Gọi bên trong một transaction của pool {@code app}.</b> Khoá sống tới hết
 *       transaction. Ngoài transaction thì câu lệnh tự commit và khoá nhả ngay khi nó vừa giữ
 *       được — hiện thực ném lỗi thay vì im lặng không khoá gì.</li>
 *   <li><b>Gọi TRƯỚC mọi câu đọc bảng xếp hạng, bằng một câu lệnh riêng.</b> Ở mức READ
 *       COMMITTED mỗi câu lệnh chụp ảnh dữ liệu lúc nó <i>bắt đầu</i>. Câu đọc watermark chạy
 *       sau khi đã giữ khoá thì thấy mọi lô mà lượt trước vừa commit; một câu vừa khoá vừa đọc
 *       thì đọc ảnh chụp từ <i>trước</i> lúc nó đứng chờ khoá — và race quay lại nguyên vẹn.</li>
 * </ol>
 *
 * <p>Mọi đường ghi {@code contest_standings} / {@code contest_problem_standings} đều phải giữ
 * khoá này. Hôm nay có hai, và cả hai nằm trong {@code StandingsUpdater}: {@code capNhat} và
 * {@code xoaTrang}. Thêm một đường <i>đọc</i> cũng giữ nó: bản chụp đóng băng của
 * {@code FreezeStandingsScheduler} đọc hai bảng ấy bằng hai câu, và cần hai câu thấy cùng một
 * bảng — một lô commit chen giữa là bản chụp vi phạm khoá ngoại.
 */
public interface StandingsLock {

    /**
     * Chờ tới khi giữ được khoá ghi bảng xếp hạng của kỳ thi này; giữ tới hết transaction.
     *
     * @throws IllegalStateException nếu không có transaction nào của pool {@code app} đang mở —
     *         lỗi lập trình, không phải lỗi vận hành
     */
    void khoaKyThi(long contestId);
}
