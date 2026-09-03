package dev.oj.judging.application.published;

import java.time.Instant;

/**
 * Tình trạng máy chấm — bề mặt công khai của {@code judging} cho trang trạng thái (FR-ADM-05),
 * dashboard vận hành (FR-ADM-04) và health check (Bước 6.7).
 *
 * <h2>Một câu hỏi, ba nơi hỏi, và chúng phải nhận cùng một câu trả lời</h2>
 * Nếu trang công khai nói "12 bài đang chờ" còn dashboard nói 9, thì một trong hai sai và
 * không ai biết cái nào. Ba nơi gọi chung một truy vấn là cách rẻ nhất để loại bỏ câu hỏi đó.
 *
 * <h2>Đếm trên {@code judge_queue}, KHÔNG BAO GIỜ trên {@code submissions}</h2>
 * Truy vấn 12 của {@code docs/sql/duong_nong.sql}. {@code judge_queue} có vài trăm dòng và
 * hàng đã chấm xong thì bị xoá khỏi nó; {@code submissions} sẽ có hàng triệu và
 * {@code COUNT(*)} ở đó là một lần quét bảng (bất biến #8, {@code oj-api/CLAUDE.md} mục 3).
 *
 * <p>Đây là ví dụ rõ nhất của việc thiết kế schema quyết định được gì: cùng một câu hỏi
 * người dùng, một bảng trả lời trong micro giây và bảng kia không trả lời được.
 */
public interface QueueStatusQuery {

    TrangThai doc();

    /**
     * @param dangCho         chưa có worker nào nhận
     * @param dangCham        đang được chấm
     * @param rejudgeDangCho  phần của {@code dangCho} là bài chấm lại (ưu tiên 10)
     * @param choLauNhat      thời điểm vào hàng của bài <b>live</b> chờ lâu nhất; {@code null}
     *                        khi không có. Tách theo ưu tiên vì cùng lý do với cái phanh của
     *                        {@code RejudgeJob}: một lô rejudge luôn là dòng chờ lâu nhất, và
     *                        để nó vào con số này là báo động giả mỗi lần chấm lại
     * @param mayChamSong     số máy chấm còn báo danh. Xem javadoc của hiện thực về độ mịn
     */
    record TrangThai(int dangCho, int dangCham, int rejudgeDangCho,
                     Instant choLauNhat, int mayChamSong) {

        public boolean rong() {
            return dangCho == 0 && dangCham == 0;
        }

        /** Mili giây bài live chờ lâu nhất đã chờ. 0 khi hàng đợi không có bài live nào. */
        public long choLauNhatMs(Instant bayGio) {
            return choLauNhat == null ? 0
                    : Math.max(0, java.time.Duration.between(choLauNhat, bayGio).toMillis());
        }

        /**
         * "Thời gian chờ ước tính" của FR-ADM-05.
         *
         * <p><b>Nó là một phép chia, và trang hiển thị phải nói đúng như thế.</b> Không có
         * cách nào đo trước được thời gian chấm một bài chưa chạy: nó phụ thuộc đề, ngôn ngữ,
         * và bài nộp. Con số này lấy throughput đã cam kết trong SLO (P4: ≥5 bài/s trên host
         * chuẩn) chia cho hàng đợi hiện tại.
         *
         * <p>Nói ra giới hạn ấy thay vì giấu nó là chủ ý — một con số ước tính được trình bày
         * như một lời hứa sẽ bị coi là lời nói dối vào đúng ngày nó sai.
         *
         * @param baiMoiGiay {@code oj.judge.throughput-estimate}
         */
        public long choUocTinhMs(double baiMoiGiay) {
            if (dangCho == 0 || baiMoiGiay <= 0) {
                return 0;
            }
            return Math.round(dangCho / baiMoiGiay * 1000);
        }
    }
}
