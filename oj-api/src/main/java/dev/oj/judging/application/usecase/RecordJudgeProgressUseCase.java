package dev.oj.judging.application.usecase;

import dev.oj.contract.JudgeProgressDto;
import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import org.springframework.stereotype.Service;

/**
 * ★ Bước 3.7 — nhận tiến độ chấm theo lô 20 test và đẩy lên luồng SSE.
 *
 * <h2>★ KHÔNG ghi xuống cơ sở dữ liệu, và đó là quyết định quan trọng nhất ở đây</h2>
 * Bản năng là {@code UPDATE submissions SET tests_done = ...} cho mỗi lô. Đừng.
 *
 * <p>{@code submissions} là bảng nóng nhất hệ thống. Một bài 100 test sinh 5 lô; 500 người
 * nộp cùng lúc là <b>2500 lần UPDATE</b> trên đúng những dòng mà đường nộp bài đang chèn vào
 * — chỉ để vẽ một thanh phần trăm sẽ biến mất sau ba mươi giây. Nó cũng phá HOT update và
 * thổi phồng bloat trên bảng mà {@code postgres-design.md} mục 4 đã phải hy sinh cả một
 * index để giữ HOT 100%.
 *
 * <p>Tiến độ là dữ liệu <b>phù du</b>: đúng trong ba mươi giây, vô nghĩa sau đó, và mất nó
 * không mất gì cả. Nó thuộc về Redis pub/sub, không thuộc về Postgres. Sự thật cuối cùng —
 * verdict — vẫn đi đường cũ qua {@code /internal/judge/result} với khoá lạc quan đầy đủ.
 *
 * <h2>Vì sao 20 test một lô chứ không phải từng test</h2>
 * Gửi từng test là lỗi DMOJ đã mắc rồi phải thêm rate limit để cứu: một bài 1000 test thành
 * 1000 request HTTP. Lô 20 cắt hai mươi lần số round-trip mà mắt người vẫn thấy thanh tiến
 * độ chạy mượt ({@code JudgeProgressDto.BATCH_SIZE}).
 *
 * <h2>Kết quả của một attempt cũ thì sao</h2>
 * Sự kiện mang {@code attempt}, và client bỏ qua sự kiện của attempt nhỏ hơn cái nó đang
 * xem. Không cần kiểm ở đây: một lô tiến độ đến muộn <b>không ghi gì cả</b>, nên nó không
 * làm hỏng được dữ liệu nào — khác hẳn với một kết quả đến muộn, thứ mà khoá lạc quan trên
 * {@code judge_queue} phải chặn (bất biến #7).
 */
@Service
public class RecordJudgeProgressUseCase {

    private final SubmissionEventBus events;

    public RecordJudgeProgressUseCase(SubmissionEventBus events) {
        this.events = events;
    }

    /**
     * Đẩy tiến độ. <b>Chỉ số đếm, không có verdict của từng test.</b>
     *
     * <p>{@link JudgeProgressDto} mang verdict từng test, và worker cần gửi chúng để M4 làm
     * được biểu đồ cho SETTER. Nhưng thứ đi ra <i>luồng SSE của thí sinh</i> chỉ là hai con
     * số: đã chạy bao nhiêu trên tổng bao nhiêu.
     *
     * <p>Đây đúng là chỗ {@code FeedbackLevel} cảnh báo: "payload mang verdict TỪNG test —
     * đẩy thẳng nó ra SSE là mở lại đúng đường rò rỉ mà mức NONE sinh ra để đóng. Lọc PHẢI
     * xảy ra trước khi publish lên Redis, không phải ở trình duyệt." Ở đây bộ lọc là phép
     * chiếu xuống hai con số — không có gì lọt qua được một phép đếm.
     */
    public void record(JudgeProgressDto progress) {
        events.publish(SubmissionEvent.progress(
                progress.submissionId(),
                progress.attempt(),
                progress.toOrdinal(),
                progress.totalTests()));
    }
}
