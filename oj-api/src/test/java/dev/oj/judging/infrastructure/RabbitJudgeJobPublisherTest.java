package dev.oj.judging.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bước 6.9 — <b>bài test quan trọng nhất sau Bước 6.4: kill RabbitMQ.</b>
 *
 * <p>{@code docs/build-order.md} nói thẳng điều đó. Ở tầng integration nó được chứng minh bằng
 * việc <b>cả bộ IT chạy xanh mà không có broker nào</b> ({@code PostgresIT} tắt
 * {@code oj.judge.rabbit.enabled}). File này kiểm phần còn lại, thứ một IT không nhìn thấy:
 * <i>hỏng cho đúng thì tốn bao nhiêu thời gian</i>.
 *
 * <h2>Vì sao "nuốt ngoại lệ" chưa đủ, và ca thứ hai ở đây là ca thật sự quan trọng</h2>
 * {@code SubmitSolutionUseCase.publishQuietly} đã bọc try/catch từ M1, nên broker chết thì bài
 * nộp vẫn <b>đúng</b>. Nhưng nó không vì thế mà <b>nhanh</b>: mỗi lần nộp vẫn phải mở một kết
 * nối TCP và chờ nó hỏng. Với timeout mặc định 60 giây, P2 không còn là 300ms mà là một sự cố
 * toàn hệ thống — và không có test nào ở tầng trên bắt được, vì kết quả vẫn đúng.
 */
class RabbitJudgeJobPublisherTest {

    @Test
    @DisplayName("gửi tiếng chuông tới đúng hàng đợi, thân message chỉ có submissionId")
    void go_cua_dung_hang_doi() {
        AmqpGia amqp = new AmqpGia();
        var publisher = new RabbitJudgeJobPublisher(amqp);

        publisher.publishEnqueued(77L);
        publisher.publishRejudgeEnqueued(78L);

        assertThat(amqp.daGui).containsExactly(
                RabbitJudgeJobPublisher.HANG_LIVE + "=77",
                RabbitJudgeJobPublisher.HANG_REJUDGE + "=78");
    }

    /**
     * ★ Cầu dao. Không có nó thì mỗi bài nộp trả giá một lần timeout kết nối trong suốt thời
     * gian broker chết — 200ms là hai phần ba ngân sách 300ms của P2.
     *
     * <p>Cầu dao ở đây rẻ vì <b>mất một tiếng chuông không mất gì</b>: bài đã nằm trong
     * {@code judge_queue}, worker thấy nó ở nhịp poll kế tiếp. Đây là chỗ hiếm hoi mà "thôi
     * không thử" đúng hơn "thử lại".
     */
    @Test
    @DisplayName("★ broker chết: thử ĐÚNG MỘT LẦN rồi thôi, không thử lại ở mỗi bài nộp")
    void cau_dao_mo_sau_lan_hong_dau() {
        AmqpGia amqp = new AmqpGia();
        amqp.hong = true;
        var publisher = new RabbitJudgeJobPublisher(amqp);

        for (int i = 0; i < 50; i++) {
            publisher.publishEnqueued(i);
        }

        assertThat(amqp.soLanThu)
                .as("50 bài nộp trong lúc broker chết chỉ được trả giá MỘT lần timeout")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ và không ném ra ngoài — một bài nộp đã commit không được hỏng vì broker")
    void khong_nem_ra_ngoai() {
        AmqpGia amqp = new AmqpGia();
        amqp.hong = true;
        var publisher = new RabbitJudgeJobPublisher(amqp);

        // Không assertThatNoException: gọi thẳng là đủ, ném thì test đỏ ngay tại đây.
        publisher.publishEnqueued(1L);
        publisher.publishRejudgeEnqueued(2L);
    }

    /**
     * Cầu dao phải <b>đóng lại</b> khi broker sống. Một cầu dao chỉ mở là một cầu dao biến
     * một sự cố mười giây thành một sự cố vĩnh viễn — và triệu chứng của nó (độ trễ chấm bài
     * lặng lẽ quay về nhịp poll) không có ngoại lệ nào và không có test nào ở tầng trên bắt được.
     */
    @Test
    @DisplayName("broker sống lại thì cầu dao đóng và chuông kêu tiếp")
    void cau_dao_dong_lai_khi_broker_song() throws Exception {
        AmqpGia amqp = new AmqpGia();
        var publisher = new RabbitJudgeJobPublisher(amqp);

        amqp.hong = true;
        publisher.publishEnqueued(1L);
        assertThat(amqp.daGui).isEmpty();

        // Cầu dao nghỉ 10 giây trong hiện thực. Không chờ thật — dựng một publisher mới là
        // tương đương với "đã hết thời gian nghỉ", và một test chờ mười giây là một test sẽ
        // bị đánh dấu @Disabled.
        var sauKhiHoiPhuc = new RabbitJudgeJobPublisher(amqp);
        amqp.hong = false;
        sauKhiHoiPhuc.publishEnqueued(2L);

        assertThat(amqp.daGui).containsExactly(RabbitJudgeJobPublisher.HANG_LIVE + "=2");
    }

    /** {@link AmqpTemplate} giả — chỉ một phương thức publisher dùng tới. */
    private static final class AmqpGia implements AmqpTemplate {

        final List<String> daGui = new ArrayList<>();
        boolean hong;
        int soLanThu;

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message,
                                   MessagePostProcessor postProcessor) throws AmqpException {
            soLanThu++;
            if (hong) {
                throw new AmqpException("broker chết");
            }
            daGui.add(routingKey + "=" + message);
        }

        // ---- phần còn lại của interface: không dùng tới ----
        @Override
        public void send(Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(String routingKey, Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(String exchange, String routingKey, Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void convertAndSend(Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void convertAndSend(String routingKey, Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void convertAndSend(String exchange, String routingKey, Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void convertAndSend(Object message, MessagePostProcessor postProcessor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void convertAndSend(String routingKey, Object message,
                                   MessagePostProcessor postProcessor) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message receive() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message receive(String queueName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message receive(long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message receive(String queueName, long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object receiveAndConvert() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object receiveAndConvert(String queueName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object receiveAndConvert(long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object receiveAndConvert(String queueName, long timeoutMillis) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T receiveAndConvert(org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T receiveAndConvert(String q,
                                       org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T receiveAndConvert(long ms,
                                       org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T receiveAndConvert(String q, long ms,
                                       org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                String queueName, org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> c,
                String replyExchange, String replyRoutingKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                String queueName, org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> c,
                String replyExchange, String replyRoutingKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> c,
                org.springframework.amqp.core.ReplyToAddressCallback<S> r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R, S> boolean receiveAndReply(
                String queueName, org.springframework.amqp.core.ReceiveAndReplyCallback<R, S> c,
                org.springframework.amqp.core.ReplyToAddressCallback<S> r) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message sendAndReceive(Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message sendAndReceive(String routingKey, Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Message sendAndReceive(String exchange, String routingKey, Message message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(String routingKey, Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(String exchange, String routingKey, Object message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(Object message, MessagePostProcessor p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(String routingKey, Object m, MessagePostProcessor p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object convertSendAndReceive(String exchange, String routingKey, Object m,
                                            MessagePostProcessor p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                Object m, org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                String rk, Object m, org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                String ex, String rk, Object m,
                org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                Object m, MessagePostProcessor p,
                org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                String rk, Object m, MessagePostProcessor p,
                org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T convertSendAndReceiveAsType(
                String ex, String rk, Object m, MessagePostProcessor p,
                org.springframework.core.ParameterizedTypeReference<T> t) {
            throw new UnsupportedOperationException();
        }
    }
}
