package dev.oj.judging.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Hai ca, và ca thứ hai mới là ca cứu một buổi sáng.
 *
 * <p>Ca một chỉ nói "có gọi khai báo không". Ca hai nói điều đắt hơn: khi broker đang chết,
 * API <b>vẫn phải khởi động</b>. Đổi {@code catch (AmqpException)} thành ném lên là biến một
 * sự cố hạ tầng chịu đựng được — đã có cầu dao, đã có reaper, đã có bảng degraded mode ở
 * {@code nfrplan.md} 7.2 — thành một API không lên nổi, tức là mất luôn cả đường nhận bài mà
 * Postgres vẫn phục vụ được.
 */
class RabbitTopologyDeclarerTest {

    @Test
    @DisplayName("khai báo topology ngay lúc khởi động, không đợi bài nộp đầu tiên")
    void khai_bao_ngay_luc_khoi_dong() {
        ConnectionFactoryGia cf = new ConnectionFactoryGia();

        new RabbitTopologyDeclarer(cf).khaiBaoLucKhoiDong();

        assertThat(cf.soLanMoKetNoi)
                .as("phải mở kết nối — đó là thứ làm RabbitAdmin khai báo "
                        + "exchange/queue/binding. Không gọi thì hàng đợi chỉ xuất hiện khi có "
                        + "bài nộp đầu tiên, và tới lúc ấy worker đã chết trong vòng lặp "
                        + "NOT_FOUND từ lâu")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ broker chết KHÔNG được làm API chết theo")
    void broker_chet_khong_lam_api_chet_theo() {
        ConnectionFactoryGia cf = new ConnectionFactoryGia();
        cf.loi = new AmqpConnectException(new java.net.ConnectException("Connection refused"));

        assertThatCode(() -> new RabbitTopologyDeclarer(cf).khaiBaoLucKhoiDong())
                .as("ApplicationReadyEvent ném là context chết là API không khởi động được. "
                        + "Bài nộp vào Postgres trước rồi mới gõ chuông, nên broker xuống chỉ "
                        + "làm việc chấm chậm lại — reaper nhặt lại phần chưa ai chuông tới "
                        + "(PublishFailsButReaperRecoversIT)")
                .doesNotThrowAnyException();

        assertThat(cf.soLanMoKetNoi)
                .as("vẫn phải THỬ một lần rồi mới nuốt lỗi")
                .isEqualTo(1);
    }

    /**
     * Fake viết tay thay cho thư viện mock — {@code CLAUDE.md} mục 6 gọi tên đúng kiểu này.
     *
     * <p>Hiện thực thẳng interface chứ không kế thừa {@code CachingConnectionFactory}:
     * {@code createConnection()} ở lớp ấy là {@code final}, không đè được. Tám method còn lại
     * trả giá trị rỗng vì lớp đang kiểm không gọi tới cái nào.
     *
     * <p>Trả {@code null} cho kết nối là đủ và có chủ đích: lớp đang kiểm cố ý KHÔNG đụng vào
     * giá trị trả về — vòng đời kết nối thuộc về chính factory, không phải người gọi.
     */
    private static final class ConnectionFactoryGia implements ConnectionFactory {

        private int soLanMoKetNoi;
        private RuntimeException loi;

        @Override
        public Connection createConnection() {
            soLanMoKetNoi++;
            if (loi != null) {
                throw loi;
            }
            return null;
        }

        @Override
        public String getHost() {
            return "localhost";
        }

        @Override
        public int getPort() {
            return 5672;
        }

        @Override
        public String getVirtualHost() {
            return "/";
        }

        @Override
        public String getUsername() {
            return "gia";
        }

        @Override
        public void addConnectionListener(ConnectionListener listener) {
        }

        @Override
        public boolean removeConnectionListener(ConnectionListener listener) {
            return false;
        }

        @Override
        public void clearConnectionListeners() {
        }
    }
}
