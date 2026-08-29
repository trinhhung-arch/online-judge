package dev.oj.judging.application.usecase;

import dev.oj.judging.application.port.SubmissionEventBus;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEvent;
import dev.oj.judging.application.port.SubmissionEventBus.SubmissionEventListener;
import dev.oj.judging.domain.Submission;
import dev.oj.judging.domain.SubmissionStatus;
import org.springframework.stereotype.Service;

/**
 * ★ Bước 3.9 — mở một luồng theo dõi bài nộp. FR-SUB-05.
 *
 * <h2>Quyền kiểm ở ĐÂY, không phải ở controller</h2>
 * Bất biến #11. {@link GetSubmissionUseCase#byId} lọc theo chủ sở hữu <b>trong câu SQL</b>
 * ({@code findForRequester}), nên bài của người khác không tồn tại chứ không phải bị từ
 * chối — và một request API trực tiếp bỏ qua giao diện cũng không đi vòng được.
 *
 * <p>Nếu quyền chỉ kiểm ở controller thì endpoint SSE trở thành một cách <b>theo dõi bài nộp
 * của người khác theo thời gian thực</b> — trong contest thì đó là thông tin quý nhất bàn
 * bên cạnh có thể muốn.
 *
 * <h2>★ Thứ tự ba bước dưới đây là để không mất sự kiện</h2>
 * <ol>
 *   <li>Đọc lần một — <b>kiểm quyền</b>. Xong rồi mới được đăng ký nghe.</li>
 *   <li>Đăng ký nghe.</li>
 *   <li>Đọc lần hai — lấy trạng thái <b>hiện tại</b> làm sự kiện đầu tiên.</li>
 * </ol>
 * Bỏ bước 3 và dùng lại kết quả bước 1 thì có một khe hở: verdict rơi vào đúng khoảng giữa
 * bước 1 và bước 2 sẽ <b>không</b> tới được ai — subscription chưa kịp có, mà ảnh chụp thì đã
 * cũ. Người dùng ngồi nhìn "đang chấm" cho tới khi timeout. Đọc lại một lần nữa là hai câu
 * query cho mỗi kết nối SSE — trên một đường không nóng, đó là cái giá rẻ nhất trong bài này.
 *
 * <p>Đổi lại, sự kiện đầu có thể trùng với một sự kiện đẩy tới ngay sau. Client phải chịu
 * được điều đó — và nó chịu được, vì mỗi sự kiện mang trạng thái đầy đủ chứ không phải một
 * phần thay đổi.
 */
@Service
public class WatchSubmissionUseCase {

    private final GetSubmissionUseCase getSubmission;
    private final SubmissionEventBus bus;

    public WatchSubmissionUseCase(GetSubmissionUseCase getSubmission, SubmissionEventBus bus) {
        this.getSubmission = getSubmission;
        this.bus = bus;
    }

    /**
     * @return {@code subscription == null} nghĩa là bài đã chấm xong từ trước — không có gì
     *         để chờ, và mở một kết nối chờ vô ích là giữ một luồng suốt 5 phút cho một sự
     *         kiện sẽ không bao giờ đến
     */
    public Watch start(long submissionId, SubmissionEventListener listener) {
        Submission authorized = getSubmission.byId(submissionId);
        // DONE là điểm dừng của luồng này. Rejudge (M6) đưa bài về QUEUED và sinh một
        // attempt mới — lúc đó client mở lại luồng, chứ không phải luồng cũ sống dậy.
        if (authorized.status() == SubmissionStatus.DONE) {
            return new Watch(eventOf(authorized), null);
        }

        AutoCloseable subscription = bus.subscribe(submissionId, listener);
        try {
            return new Watch(eventOf(getSubmission.byId(submissionId)), subscription);
        } catch (RuntimeException e) {
            closeQuietly(subscription);
            throw e;
        }
    }

    /**
     * Ảnh chụp trạng thái hiện tại, dạng một {@link SubmissionEvent} — cùng kiểu dữ liệu với
     * sự kiện đẩy tới sau đó, nên client chỉ có <b>một</b> đường xử lý thay vì hai.
     */
    private static SubmissionEvent eventOf(Submission submission) {
        return new SubmissionEvent(
                submission.id(),
                submission.attempt(),
                submission.status().name(),
                submission.outcome() == null ? null : submission.outcome().verdict().name(),
                null, null,
                java.time.Instant.now());
    }

    private static void closeQuietly(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception ignored) {
            // Đang trên đường ném một ngoại lệ khác; nuốt cái này để không che mất nguyên nhân.
        }
    }

    /** Trạng thái lúc mở luồng + lệnh huỷ đăng ký. {@code close()} phải được gọi khi đứt. */
    public record Watch(SubmissionEvent current, AutoCloseable subscription)
            implements AutoCloseable {

        public boolean alreadyFinished() {
            return subscription == null;
        }

        @Override
        public void close() {
            if (subscription != null) {
                closeQuietly(subscription);
            }
        }
    }
}
