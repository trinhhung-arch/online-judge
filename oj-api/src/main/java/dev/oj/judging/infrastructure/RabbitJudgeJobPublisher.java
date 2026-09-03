package dev.oj.judging.infrastructure;

import dev.oj.judging.application.port.JudgeJobPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ★ Bước 6.4 — chuyển giao việc từ "worker tự hỏi" sang "server gõ cửa".
 *
 * <h2>Nó thay thế đúng một thứ: nhịp ngủ 500ms của worker</h2>
 * Ngân sách {@code enqueue → worker claim} là <b>100ms</b> ({@code nfrplan.md} 2.1). Với
 * {@code idle-poll = 500ms} thì trung bình mất 250ms và tệ nhất là 500ms — vượt ngân sách
 * bằng một khoảng thời gian mà máy chấm ngồi không. Đó là toàn bộ vấn đề bước này giải.
 *
 * <h2>★ Thông điệp là MỘT TIẾNG CHUÔNG, không phải một gói việc</h2>
 * Thân message chỉ có {@code submissionId}, và <b>worker không dùng con số đó để chọn bài</b>
 * — nó vẫn gọi {@code /internal/judge/claim} như từ M1. Ba hệ quả, và cả ba đều là lý do chọn
 * thiết kế này:
 *
 * <ul>
 *   <li><b>{@code oj-contract} không đổi một dòng.</b> Bốn endpoint trong {@code JudgeEndpoints}
 *       giữ nguyên, nên đây không phải một PR chạm hợp đồng đóng băng
 *       ({@code CLAUDE.md} mục 5.1).</li>
 *   <li><b>Mã nguồn người dùng không vào ổ đĩa của broker.</b> Nhét cả {@code JudgeJobDto}
 *       (kèm 64KB source) vào message là tạo một bản sao source ở một hệ thống thứ ba, nơi
 *       không có phân quyền của ta và không ai nghĩ tới lúc rà bất biến #9.</li>
 *   <li><b>Message không bao giờ cũ.</b> Reaper thu hồi một bài rồi tăng {@code attempt}; một
 *       message mang payload sẽ mang {@code attempt} cũ và worker chấm theo một ảnh chụp đã
 *       sai. Một tiếng chuông thì không có gì để cũ.</li>
 * </ul>
 *
 * <h2>Thứ tự ưu tiên đến từ Postgres, không từ broker</h2>
 * Hai hàng đợi {@code judge.live} / {@code judge.rejudge} tồn tại để đo và để chặn riêng
 * (ngắt binding của rejudge không đụng tới live). Nhưng <b>chúng không quyết định bài nào được
 * chấm trước</b>: câu {@code claim} xếp theo {@code (priority, enqueued_at)} trên
 * {@code ix_judge_queue_ready}, nên một tiếng chuông từ {@code judge.rejudge} vẫn làm worker
 * nhận bài live đang chờ, nếu có. RabbitMQ không cần biết luật ưu tiên, và đó là lý do đổi
 * transport rẻ.
 *
 * <h2>Publish hỏng thì bỏ qua — {@code judge_queue} mới là sự thật</h2>
 * Hàng đã commit trước khi hàm này được gọi. Broker chết thì worker quay về nhịp poll và mọi
 * bài vẫn được chấm, chỉ chậm hơn vài trăm mili giây ({@code nfrplan.md} 7.2, dòng RabbitMQ
 * của bảng degraded mode). {@code SubmitSolutionUseCase.publishQuietly} đã bọc sẵn.
 *
 * <h2>★ "Bỏ qua" chưa đủ — Bước 6.9 và cái bẫy 300ms</h2>
 * Nuốt ngoại lệ giữ cho bài nộp <b>đúng</b> khi broker chết, nhưng không giữ cho nó
 * <b>nhanh</b>. Lời gọi {@code convertAndSend} phải mở một kết nối TCP trước khi biết là hỏng,
 * và một cổng không ai nghe trên máy chủ khác thì không từ chối ngay — nó im lặng cho tới khi
 * hết timeout. Với timeout mặc định (60 giây), mỗi lần nộp bài treo 60 giây trong lúc broker
 * chết, và P2 không còn là 300ms mà là một sự cố toàn hệ thống.
 *
 * <p>Hai lớp chữa, và cần cả hai:
 * <ul>
 *   <li>{@code spring.rabbitmq.connection-timeout: 200ms} — trần cho <i>một</i> lần thử.</li>
 *   <li><b>Cầu dao</b> ở dưới — sau một lần hỏng, ngừng thử trong {@link #NGHI_SAU_KHI_HONG}.
 *       Không có nó thì mỗi bài nộp vẫn trả giá 200ms, và 200ms là hai phần ba ngân sách.</li>
 * </ul>
 *
 * <p>Cầu dao ở đây rẻ vì <b>mất một tiếng chuông không mất gì</b>: bài đã nằm trong
 * {@code judge_queue}, worker thấy nó ở nhịp poll kế tiếp. Đây là chỗ hiếm hoi mà "thử lại"
 * kém hơn hẳn "thôi không thử".
 */
public class RabbitJudgeJobPublisher implements JudgeJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitJudgeJobPublisher.class);

    /**
     * Bốn cái tên này <b>cũng nằm trong {@code oj-worker/src/main/resources/application.yml}</b>.
     * Đó chính là loại trùng lặp mà {@code JudgeEndpoints} sinh ra để xoá bỏ — nhưng đặt chúng
     * vào {@code oj-contract} là đổi hợp đồng đóng băng, việc phải hỏi người
     * ({@code CLAUDE.md} mục 5.1). Đường thoát: {@code HopDongVanHanhTest} đọc cả hai file yml
     * và đỏ ngay lúc chúng lệch nhau, nên cái bẫy "worker nghe một hàng đợi không ai gửi vào"
     * vẫn bị bắt bởi CI thay vì bởi một buổi tối gỡ lỗi.
     */
    public static final String EXCHANGE = "oj.judge";
    public static final String EXCHANGE_CHET = "oj.judge.dlx";
    public static final String HANG_LIVE = "judge.live";
    public static final String HANG_REJUDGE = "judge.rejudge";
    public static final String HANG_CHET = "judge.dead";

    /** Bước 6.4: "DLQ sau 3 lần". Quorum queue ép được điều này bằng một tham số. */
    private static final int TRAN_GIAO_LAI = 3;

    /**
     * Cầu dao mở trong bao lâu sau một lần publish hỏng. Xem javadoc lớp.
     *
     * <p>Không đưa vào {@code application.yml}: đây không phải một ngưỡng nghiệp vụ ai cần
     * chỉnh, mà là một chi tiết của việc hỏng cho đúng. Mười giây đủ ngắn để độ trễ giao việc
     * trở lại bình thường ngay sau khi broker sống lại, và đủ dài để một sự cố kéo dài không
     * tính thêm một lần timeout nào vào ngân sách 300ms.
     */
    private static final long NGHI_SAU_KHI_HONG_MS = 10_000;

    private final AmqpTemplate amqp;

    /**
     * Thời điểm được phép thử lại. {@code 0} = cầu dao đóng (bình thường).
     *
     * <p>{@code volatile long} thay vì một đối tượng trạng thái: đọc/ghi một {@code long} là
     * nguyên tử trên JVM 64-bit, nhiều luồng nộp bài cùng lúc chỉ có thể ghi đè nhau bằng
     * những giá trị gần bằng nhau, và hậu quả xấu nhất là một lần thử thừa. Một khoá ở đây là
     * một điểm tranh chấp trên đúng đường nóng, để đổi lấy một sự chính xác không ai cần.
     */
    private volatile long nghiToi;

    public RabbitJudgeJobPublisher(AmqpTemplate amqp) {
        this.amqp = amqp;
    }

    @Override
    public void publishEnqueued(long submissionId) {
        goCua(HANG_LIVE, submissionId);
    }

    @Override
    public void publishRejudgeEnqueued(long submissionId) {
        goCua(HANG_REJUDGE, submissionId);
    }

    private void goCua(String hangDoi, long submissionId) {
        long bayGio = System.currentTimeMillis();
        if (bayGio < nghiToi) {
            // Cầu dao đang mở. KHÔNG ném: người gọi đã bọc try/catch, nhưng ném ở đây biến
            // một trạng thái bình thường (broker đang xuống, đã biết) thành một dòng log lỗi
            // cho mỗi bài nộp.
            return;
        }
        try {
            amqp.convertAndSend(EXCHANGE, hangDoi, Long.toString(submissionId), m -> {
                // NON_PERSISTENT: một tiếng chuông mất đi không mất bài nào — hàng vẫn nằm
                // trong judge_queue và reaper nhặt. Ghi mỗi tiếng chuông xuống đĩa của broker
                // là trả giá I/O cho một bảo đảm mà Postgres đã cho rồi.
                m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
                return m;
            });
            if (nghiToi != 0) {
                nghiToi = 0;
                log.info("RabbitMQ đã trở lại — gõ cửa hoạt động lại.");
            }
            log.trace("Đã gõ cửa {} cho submission {}", hangDoi, submissionId);
        } catch (RuntimeException e) {
            if (nghiToi == 0) {
                log.warn("Không gõ cửa được ({}). Ngừng thử trong {}ms; bài nộp vẫn vào "
                        + "judge_queue và worker sẽ nhặt theo nhịp poll.",
                        e.toString(), NGHI_SAU_KHI_HONG_MS);
            }
            nghiToi = System.currentTimeMillis() + NGHI_SAU_KHI_HONG_MS;
        }
    }

    /**
     * Hình trạng hàng đợi <b>và</b> điều kiện bật/tắt, gói chung vào một chỗ.
     *
     * <h2>Vì sao điều kiện là một thuộc tính chứ không phải {@code @ConditionalOnMissingBean}</h2>
     * Bản M1 của {@code NoopJudgeJobPublisher} dựa vào {@code @ConditionalOnMissingBean}, và
     * cách đó <b>chỉ đáng tin trong auto-configuration</b> — với hai lớp {@code @Configuration}
     * do component scan tìm thấy, thứ tự xử lý không được bảo đảm, nên "bean nào thắng" trở
     * thành chuyện may rủi. Một hệ thống chấm bài không được phép quyết định transport của
     * mình bằng thứ tự quét package.
     *
     * <p>Một thuộc tính, hai giá trị đối nhau: đúng một hiện thực tồn tại trong mọi cấu hình,
     * và đọc file yml là biết cái nào.
     *
     * <h2>Khai báo ở đây, không ở worker</h2>
     * Hai bên cùng khai báo nghĩa là hai bộ tham số có thể lệch, và RabbitMQ từ chối một khai
     * báo lệch bằng {@code PRECONDITION_FAILED} — lỗi đó đóng cả channel, và triệu chứng nhìn
     * thấy được chỉ là "worker im lặng".
     */
    @ConditionalOnProperty(name = "oj.judge.rabbit.enabled", havingValue = "true",
            matchIfMissing = true)
    @Configuration
    public static class Topology {

        @Bean
        public RabbitJudgeJobPublisher rabbitJudgeJobPublisher(AmqpTemplate amqp) {
            return new RabbitJudgeJobPublisher(amqp);
        }

        @Bean
        public DirectExchange judgeExchange() {
            return new DirectExchange(EXCHANGE, true, false);
        }

        @Bean
        public FanoutExchange judgeDeadExchange() {
            return new FanoutExchange(EXCHANGE_CHET, true, false);
        }

        /**
         * Quorum queue — Bước 6.4. Bản sao trên nhiều node, và quan trọng hơn ở quy mô một
         * máy: <b>{@code x-delivery-limit} chỉ có ở quorum queue</b>, và nó là cách khai báo
         * "DLQ sau 3 lần" bằng một tham số thay vì một chuỗi retry interceptor phải cấu hình
         * khớp nhau ở cả hai phía.
         */
        private static Queue hangCham(String ten) {
            return QueueBuilder.durable(ten)
                    .quorum()
                    .deadLetterExchange(EXCHANGE_CHET)
                    .withArgument("x-delivery-limit", TRAN_GIAO_LAI)
                    .build();
        }

        @Bean
        public Queue hangLive() {
            return hangCham(HANG_LIVE);
        }

        @Bean
        public Queue hangRejudge() {
            return hangCham(HANG_REJUDGE);
        }

        /**
         * Nơi những tiếng chuông không ai xử lý được đi tới. <b>Không có consumer</b>, cố ý:
         * nó là một chỉ số vận hành (dashboard FR-ADM-04 đọc độ dài của nó), không phải một
         * đường cứu dữ liệu — đường cứu dữ liệu là reaper.
         */
        @Bean
        public Queue hangChet() {
            return QueueBuilder.durable(HANG_CHET).quorum().build();
        }

        @Bean
        public Declarables judgeBindings(DirectExchange judgeExchange,
                                         FanoutExchange judgeDeadExchange, Queue hangLive,
                                         Queue hangRejudge, Queue hangChet) {
            return new Declarables(
                    BindingBuilder.bind(hangLive).to(judgeExchange).with(HANG_LIVE),
                    BindingBuilder.bind(hangRejudge).to(judgeExchange).with(HANG_REJUDGE),
                    BindingBuilder.bind(hangChet).to(judgeDeadExchange));
        }
    }
}
