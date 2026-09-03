package dev.oj.judging.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * ★ Luật điều tiết của chấm lại hàng loạt — FR-ADM-01, Bước 6.3.
 *
 * <h2>Vấn đề thật, bằng số</h2>
 * Một đề phổ biến có 10.000 bài nộp. Đẩy hết vào cùng hàng đợi với bài nộp trực tiếp, ở
 * throughput 5 bài/s, là hàng đợi tắc <b>33 phút</b> ({@code frplan.md} mâu thuẫn 3.2). Người
 * nộp bài trong khoảng đó chờ nửa tiếng cho một verdict. Nếu đúng lúc contest thì hỏng contest.
 *
 * <h2>Hai lớp phòng thủ, và chúng chặn hai thứ khác nhau</h2>
 * <ol>
 *   <li><b>{@code priority = 10}</b> ({@link DomainRules#PRIORITY_REJUDGE}) — worker luôn hút
 *       cạn {@code priority = 0} trước, vì {@code ix_judge_queue_ready} xếp theo
 *       {@code (priority, enqueued_at)}. Lớp này chặn <i>thứ tự</i>: một bài rejudge không bao
 *       giờ được chấm trước một bài nộp trực tiếp đang chờ.</li>
 *   <li><b>Trần số dòng rejudge đang nằm trong hàng đợi</b> — lớp này chặn <i>năng lực</i>.
 *       Thứ tự thôi thì chưa đủ: nếu 10.000 dòng rejudge đã nằm sẵn trong queue thì tại mọi
 *       thời điểm cả sáu slot đều đang bận với chúng, và một bài nộp trực tiếp vừa đến vẫn
 *       phải chờ slot đầu tiên rảnh. Giữ tối đa {@code tranDangCho} dòng nghĩa là tối đa
 *       ngần ấy slot có thể bận vì rejudge — đó chính là "30% năng lực chấm".</li>
 * </ol>
 *
 * <h2>Và một cái phanh</h2>
 * FR-ADM-01: <i>"tự động giảm về 0 khi queue_wait của live vượt 5s"</i>. Hai lớp trên là
 * phòng ngừa tĩnh; cái phanh là phản hồi động — khi hàng đợi trực tiếp <b>thật sự</b> đang
 * chờ lâu (worker chết bớt, máy throttle nhiệt, một đợt nộp dồn), rejudge dừng hẳn cho tới
 * khi hết nghẽn.
 *
 * <p><b>Chỉ số của phanh là thời gian chờ của bài LIVE, không phải của cả hàng đợi.</b> Đây là
 * chỗ dễ sai nhất trong cả bước: dòng rejudge do chính job này đẩy vào cũng nằm trong
 * {@code judge_queue}, và chúng cố ý bị xếp sau nên chúng <i>luôn</i> là dòng chờ lâu nhất.
 * Lấy thời gian chờ của cả bảng thì job tự đạp phanh của chính mình sau lô đầu tiên, rồi
 * không bao giờ chạy tiếp — một lỗi im lặng, biểu hiện là "rejudge chạy mãi không xong".
 *
 * <h2>Java thuần, không Spring — luật ArchUnit 1</h2>
 * Ngưỡng đến qua tham số, không qua {@code AppProperties}. Nhờ vậy toàn bộ luật này kiểm được
 * bằng unit test không cần context, và đó là điều kiện để nó thật sự có test.
 */
public final class RejudgeJob {

    private RejudgeJob() {
    }

    /**
     * Còn được đẩy thêm bao nhiêu bài vào hàng đợi ngay bây giờ.
     *
     * @param nhip           ảnh chụp hàng đợi, tách theo mức ưu tiên
     * @param tranDangCho    trần số dòng rejudge được phép nằm chờ cùng lúc — 30% năng lực
     * @param phanhKhiLiveCho  bài live chờ lâu hơn ngần này thì trả 0
     * @param bayGio         đồng hồ ứng dụng, cùng nguồn với mọi phép kiểm thời gian khác
     * @return 0 nghĩa là <b>chưa phải lúc</b> (không phải "đã xong"). Người gọi nhường lượt
     *         chứ không kết thúc job
     */
    public static int suatConLai(NhipHangDoi nhip, int tranDangCho,
                                 Duration phanhKhiLiveCho, Instant bayGio) {
        if (tranDangCho < 1) {
            throw new IllegalArgumentException("tranDangCho phải ≥ 1, nhận " + tranDangCho);
        }
        if (nhip.liveDangChoLauHon(phanhKhiLiveCho, bayGio)) {
            return 0;
        }
        return Math.max(0, tranDangCho - nhip.rejudgeDangCho());
    }

    /**
     * Ảnh chụp {@code judge_queue} <b>tách theo mức ưu tiên</b>.
     *
     * @param rejudgeDangCho   số dòng {@code priority = 10} chưa được claim
     * @param liveChoLauNhat   thời điểm vào hàng của bài {@code priority = 0} chờ lâu nhất;
     *                         {@code null} khi không có bài live nào đang chờ
     */
    public record NhipHangDoi(int rejudgeDangCho, Instant liveChoLauNhat) {

        public boolean liveDangChoLauHon(Duration nguong, Instant bayGio) {
            return liveChoLauNhat != null
                    && Duration.between(liveChoLauNhat, bayGio).compareTo(nguong) > 0;
        }
    }
}
