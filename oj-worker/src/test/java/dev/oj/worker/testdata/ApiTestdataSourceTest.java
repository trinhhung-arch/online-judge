package dev.oj.worker.testdata;

import dev.oj.worker.client.JudgeApiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * ★ Mắt xích từng thiếu: worker lấy testdata bằng đường nào.
 *
 * <p>Không có lớp này thì hiện thực {@code TestdataSource} duy nhất đọc một thư mục cục bộ mà
 * không gì đổ dữ liệu vào — mọi bài nộp trả {@code IE} ngay khi testdata được nạp qua API.
 * Xem javadoc của {@link ApiTestdataSource}.
 */
class ApiTestdataSourceTest {

    private static final String SHA = "a".repeat(64);

    @Test
    @DisplayName("trả đúng byte mà API trả về, không đụng vào nội dung")
    void tra_dung_byte() {
        byte[] noiDung = "3 4\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var nguon = new ApiTestdataSource(clientTraVe(noiDung));

        assertThat(nguon.fetch(SHA)).isEqualTo(noiDung);
    }

    /**
     * ★ Hỏng phải thành {@link TestdataUnavailableException}, không phải một
     * {@code JudgeApiException} lọt lên trên.
     *
     * <p>Khác biệt không phải hình thức: {@code JudgeLoop} bắt {@code JudgeApiException} và
     * hiểu là <i>"API đang xuống"</i> — nó ngủ rồi thử xin việc lại, và <b>lượt chấm hiện tại
     * biến mất mà API không được báo gì</b>, phải chờ hết lease 120 giây. Còn
     * {@code TestdataUnavailableException} đi tới {@code JobExecutor} và thành {@code IE},
     * nên API cho chấm lại ngay (FR-SUB-12).
     */
    @Test
    @DisplayName("★ API hỏng → TestdataUnavailableException, để JobExecutor trả IE")
    void hong_thi_thanh_testdata_unavailable() {
        var nguon = new ApiTestdataSource(clientNem());

        assertThatExceptionOfType(TestdataUnavailableException.class)
                .isThrownBy(() -> nguon.fetch(SHA));
    }

    /**
     * ★ Bất biến #1 và #9: thông báo lỗi không được mang nội dung testcase, và không được
     * mang cả hash đầy đủ — một hash đầy đủ trong log là một khoá tải về, và log thì đi xa
     * hơn người ta tưởng.
     */
    @Test
    @DisplayName("★ thông báo lỗi không chứa nội dung, và chỉ chứa 8 ký tự đầu của hash")
    void thong_bao_loi_khong_ro_ri() {
        var nguon = new ApiTestdataSource(clientNem());

        assertThatExceptionOfType(TestdataUnavailableException.class)
                .isThrownBy(() -> nguon.fetch(SHA))
                .satisfies(e -> {
                    assertThat(e.getMessage()).doesNotContain(SHA);
                    assertThat(e.getMessage()).contains(SHA.substring(0, 8));
                });
    }

    // -------------------------------------------------------------------------

    private static JudgeApiClient clientTraVe(byte[] noiDung) {
        return new JudgeApiClient(dev.oj.worker.WorkerFixtures.properties(
                java.nio.file.Path.of("/tmp/oj-test-cache")),
                org.springframework.web.client.RestClient.builder()) {
            @Override
            public byte[] fetchTestdata(String sha256) {
                return noiDung;
            }
        };
    }

    private static JudgeApiClient clientNem() {
        return new JudgeApiClient(dev.oj.worker.WorkerFixtures.properties(
                java.nio.file.Path.of("/tmp/oj-test-cache")),
                org.springframework.web.client.RestClient.builder()) {
            @Override
            public byte[] fetchTestdata(String sha256) {
                throw new JudgeApiException("API 503", true);
            }
        };
    }
}
