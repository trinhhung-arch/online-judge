package dev.oj.judging.infrastructure;

import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Bộ hẹn giờ của reaper. <b>Chỉ là bộ hẹn giờ</b> — toàn bộ luật nghiệp vụ nằm ở
 * {@link ReapStaleJobsUseCase}, nơi test được bằng fake repository mà không cần Spring.
 *
 * <p>Tách hai thứ ra vì chúng hỏng theo hai kiểu khác nhau: luật sai thì unit test bắt được;
 * lịch chạy sai (chu kỳ dài hơn lease, hoặc quên {@code @EnableScheduling}) thì chỉ lộ ra khi
 * có một worker chết thật. Gộp lại thì cả hai cùng phải chạy Spring context để kiểm.
 *
 * <h2>{@code fixedDelay}, không phải {@code fixedRate}</h2>
 * {@code fixedRate} tính từ lúc <i>bắt đầu</i> lần trước, nên nếu một lượt reap chạy lâu hơn
 * chu kỳ — DB đang tải nặng, đúng lúc ta cần reaper nhất — Spring sẽ xếp hàng các lượt kế
 * tiếp và bắn dồn. {@code fixedDelay} đếm từ lúc <i>kết thúc</i>, nên chậm thì thưa ra chứ
 * không chồng lên nhau.
 *
 * <p>Chu kỳ đọc từ {@code oj.judge.reaper-interval} (15s) và {@code AppProperties.Judge} ép
 * lúc boot rằng nó <b>nhỏ hơn</b> {@code oj.judge.lease} (120s) — chạy thưa hơn lease nghĩa
 * là một bài kẹt phải chờ tới hai chu kỳ mới được nhặt lại.
 */
@Component
public class StaleJobReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleJobReaper.class);

    private final ReapStaleJobsUseCase reapStaleJobs;

    public StaleJobReaper(ReapStaleJobsUseCase reapStaleJobs) {
        this.reapStaleJobs = reapStaleJobs;
    }

    /**
     * <b>Nuốt mọi ngoại lệ, có chủ ý.</b> Spring huỷ hẳn một tác vụ {@code @Scheduled} nếu nó
     * ném ra ngoài — nghĩa là một lần DB chớp tắt sẽ làm reaper <i>ngừng chạy vĩnh viễn</i>,
     * im lặng, cho tới lần restart tiếp theo. Và reaper là mạng an toàn cho năm loại sự cố:
     * mất nó là mất bảo đảm R1 mà không có gì báo.
     */
    @Scheduled(fixedDelayString = "${oj.judge.reaper-interval}")
    public void reap() {
        try {
            reapStaleJobs.reap();
        } catch (Exception e) {
            log.error("Lượt reap thất bại — sẽ thử lại ở chu kỳ sau", e);
        }
    }
}
