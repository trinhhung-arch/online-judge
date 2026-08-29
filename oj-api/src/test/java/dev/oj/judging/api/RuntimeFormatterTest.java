package dev.oj.judging.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeFormatterTest {

    @ParameterizedTest(name = "{0}ms -> {1}ms")
    @CsvSource({"0,0", "4,0", "5,10", "14,10", "15,20", "23,20", "21,20", "1999,2000"})
    @DisplayName("★ FR-SUB-11 — làm tròn 10ms, và 23 với 21 phải ra CÙNG một số")
    void lam_tron_10ms(int input, int expected) {
        assertThat(RuntimeFormatter.roundMs(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("null giữ nguyên null — CE và IE không có số đo nào")
    void null_giu_nguyen() {
        assertThat(RuntimeFormatter.roundMs(null)).isNull();
    }

    /**
     * Nếu ai đó bỏ {@code Locale.ROOT}, máy chạy {@code vi-VN} sẽ in {@code "2,03s"} — mà
     * một nửa thế giới đọc chuỗi đó thành hai nghìn linh ba giây. Test này chạy dưới locale
     * dùng dấu phẩy để bắt đúng ngày đó.
     */
    @Test
    @DisplayName("★ dấu thập phân là dấu chấm, kể cả trên máy dùng locale dấu phẩy")
    void khong_phu_thuoc_locale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("vi-VN"));
            assertThat(RuntimeFormatter.seconds(2030)).isEqualTo("2.03s");
            assertThat(RuntimeFormatter.memory(12_698)).isEqualTo("12.4 MB");
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("thiếu số đo thì in gạch ngang, không in 0")
    void thieu_so_do() {
        assertThat(RuntimeFormatter.seconds(null)).isEqualTo("—");
        assertThat(RuntimeFormatter.memory(null)).isEqualTo("—");
    }
}
