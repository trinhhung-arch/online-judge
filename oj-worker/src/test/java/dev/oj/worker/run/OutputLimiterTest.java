package dev.oj.worker.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class OutputLimiterTest {

    @Test
    @DisplayName("dưới trần thì giữ nguyên vẹn")
    void duoiTran() throws IOException {
        var captured = OutputLimiter.drain(stream("42\n"), 1024);

        assertThat(captured.text()).isEqualTo("42\n");
        assertThat(captured.truncated()).isFalse();
        assertThat(captured.totalBytes()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ vượt trần: cắt phần giữ lại NHƯNG vẫn đọc tới hết")
    void vuotTranVanDocHet() throws IOException {
        byte[] data = new byte[100_000];
        java.util.Arrays.fill(data, (byte) 'A');

        var captured = OutputLimiter.drain(new ByteArrayInputStream(data), 1000);

        assertThat(captured.bytes()).hasSize(1000);
        assertThat(captured.truncated()).isTrue();
        assertThat(captured.totalBytes())
                .as("phải đếm hết 100.000: ngừng đọc thì pipe đầy, chương trình kẹt ở write() "
                        + "và giữ một judge slot cho tới khi hết wall-time")
                .isEqualTo(100_000);
    }

    @Test
    @DisplayName("đúng bằng trần thì không coi là bị cắt")
    void dungBangTran() throws IOException {
        assertThat(OutputLimiter.drain(stream("abcde"), 5).truncated()).isFalse();
    }

    @Test
    @DisplayName("rỗng cũng là một kết quả hợp lệ, không phải lỗi")
    void rong() throws IOException {
        var captured = OutputLimiter.drain(stream(""), 10);
        assertThat(captured.bytes()).isEmpty();
        assertThat(captured.truncated()).isFalse();
    }

    private static ByteArrayInputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
