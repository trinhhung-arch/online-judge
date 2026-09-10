package dev.oj.worker.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Giãn nhịp xin việc khi API xuống, và <b>ghi log một lần</b> thay vì một lần mỗi thử.
 *
 * <h2>Sự cố đã đo, 2026-09-10</h2>
 * {@code JudgeLoop} bắt {@code JudgeApiException} rồi ghi một dòng WARN cho <i>mỗi</i> lần
 * claim hỏng, với nhịp đứng yên ở {@code idle-poll} = 500ms. Sáu slot × 2 lần/giây =
 * <b>12 dòng mỗi giây</b>, không đổi dù API đã chết bao lâu. API chết một tiếng là hàng chục
 * nghìn dòng, và mọi thứ khác — kể cả hai dòng hiệu chuẩn máy — chìm nghỉm giữa chúng.
 *
 * <p>Chú thích ở chỗ cũ viết <i>"đừng quay vòng dội request vào một hệ thống đang có sự cố"</i>,
 * nhưng 12 request/giây vào một cổng đã chết thì đúng là dội. Ý định đã có, cơ chế thì chưa.
 *
 * <h2>Hai thứ tách rời, và tách là có lý do</h2>
 * <ul>
 *   <li><b>Nhịp chờ tính theo TỪNG slot</b> ({@link #cho}): đường cong giãn nhịp không phụ
 *       thuộc {@code slots}, nên đọc log là biết slot ấy đã hỏng lần thứ mấy. Dùng một bộ đếm
 *       chung thì sáu slot đẩy nó lên gấp sáu và con số mất hết ý nghĩa.</li>
 *   <li><b>Trạng thái log dùng CHUNG</b> ({@link #ghiNhanMat} / {@link #ghiNhanCoLai}): "API
 *       chết" là chuyện của API, không phải của một slot. Sáu slot cùng phát hiện thì vẫn chỉ
 *       đáng một dòng.</li>
 * </ul>
 *
 * <h2>Vì sao trần KHÔNG nằm trong {@code application.yml}</h2>
 * Cùng lý do {@code RabbitJudgeJobPublisher.NGHI_SAU_KHI_HONG_MS} phía API không nằm ở đó:
 * đây không phải một ngưỡng nghiệp vụ ai cần chỉnh, mà là một chi tiết của việc <i>hỏng cho
 * đúng</i>. Nó cũng không phải một con số độc lập — nó là bội của {@code idle-poll}, nên tách
 * ra hai chỗ là tạo cơ hội cho chúng lệch nhau.
 *
 * <h2>Giãn nhịp KHÔNG làm chậm việc chấm</h2>
 * Nhịp chỉ giãn sau khi API <b>ném lỗi</b>. API sống mà hàng đợi rỗng thì slot đi đường khác:
 * chờ tiếng chuông của {@code JudgeDoorbell}. Và ngay khi API trả lời lại, bộ đếm về 0 —
 * không có chuyện worker còn ngủ 30 giây trong lúc bài đã vào hàng.
 */
public class ClaimBackoff {

    private static final Logger log = LoggerFactory.getLogger(ClaimBackoff.class);

    /** Trần = {@code idle-poll} × 60. Với 500ms là 30 giây: đủ thưa để không dội, đủ dày để
     *  API sống lại thì chậm nhất nửa phút sau worker vào việc — dưới xa lease 120s. */
    private static final int BOI_TRAN = 60;

    /** Chặn tràn khi dịch bit. {@code 2^20} lần nhịp cơ sở đã vượt trần từ lâu. */
    private static final int MU_TOI_DA = 20;

    private final Duration nhipCoSo;
    private final Duration tran;

    /** {@code true} = đang trong trạng thái mất kết nối và ĐÃ ghi dòng log báo mất. */
    private final AtomicBoolean dangMat = new AtomicBoolean();

    public ClaimBackoff(Duration nhipCoSo) {
        if (nhipCoSo == null || nhipCoSo.isZero() || nhipCoSo.isNegative()) {
            throw new IllegalArgumentException("nhịp cơ sở phải dương: " + nhipCoSo);
        }
        this.nhipCoSo = nhipCoSo;
        this.tran = nhipCoSo.multipliedBy(BOI_TRAN);
    }

    /**
     * Thời gian slot nên ngủ sau lần hỏng liên tiếp thứ {@code lienTiep} <b>của chính nó</b>.
     *
     * <p>Với nhịp cơ sở 500ms: 0,5s · 1s · 2s · 4s · 8s · 16s · 30s · 30s…
     */
    public Duration cho(int lienTiep) {
        if (lienTiep < 1) {
            throw new IllegalArgumentException("lienTiep phải ≥ 1, nhận: " + lienTiep);
        }
        Duration cho = nhipCoSo.multipliedBy(1L << Math.min(lienTiep - 1, MU_TOI_DA));
        return cho.compareTo(tran) > 0 ? tran : cho;
    }

    /**
     * Đánh dấu API đang xuống. Ghi log <b>đúng một lần</b> cho cả worker.
     *
     * @return {@code true} nếu chính lời gọi này làm đổi trạng thái và đã ghi log. Sáu slot
     *         cùng gọi thì đúng một cái nhận {@code true} — ca kiểm dựa vào đây, và người đọc
     *         cũng cần biết là chỉ một dòng được ghi
     */
    public boolean ghiNhanMat(String lyDo, Duration cho) {
        if (!dangMat.compareAndSet(false, true)) {
            return false;
        }
        log.warn("Mất kết nối tới API ({}). Các slot giãn nhịp xin việc — lần này chờ {}, "
                        + "tối đa {}. KHÔNG ghi thêm dòng nào cho tới khi API trả lời lại.",
                lyDo, cho, tran);
        return true;
    }

    /**
     * Đánh dấu API đã trả lời. Ghi log đúng một lần, và chỉ khi trước đó đang mất.
     *
     * <p>Gọi ở mỗi lần claim thành công nên phải rẻ: đọc một {@code volatile} rồi thoát ngay
     * trong trường hợp thường gặp.
     *
     * @return {@code true} nếu chính lời gọi này ghi dòng báo hồi phục
     */
    public boolean ghiNhanCoLai() {
        if (!dangMat.get() || !dangMat.compareAndSet(true, false)) {
            return false;
        }
        log.info("API trả lời lại — các slot quay về nhịp bình thường {}.", nhipCoSo);
        return true;
    }
}
