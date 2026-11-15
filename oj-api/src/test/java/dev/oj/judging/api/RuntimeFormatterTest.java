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

    @ParameterizedTest(name = "{0} KB -> {1} KB")
    @CsvSource({"0,0", "1,1024", "1024,1024", "1025,2048", "12698,13312", "65536,65536", "65537,66560"})
    @DisplayName("★ BM-2 · bộ nhớ làm tròn LÊN tới MB: không bao giờ báo thấp hơn số đo thật")
    void lam_tron_bo_nho_len_mb(int input, int expected) {
        assertThat(RuntimeFormatter.roundKb(input)).isEqualTo(expected);
    }

    @Test
    @DisplayName("★ BM-2 · hai lần đo lệch vài KB ra CÙNG một số — con số bộ nhớ không còn là kênh nhắn tin theo từng KB")
    void lech_vai_kb_cung_mot_so() {
        assertThat(RuntimeFormatter.roundKb(12_289)).isEqualTo(RuntimeFormatter.roundKb(13_312));
        assertThat(RuntimeFormatter.memory(12_289)).isEqualTo(RuntimeFormatter.memory(13_000))
                .isEqualTo("13 MB");
        assertThat(RuntimeFormatter.roundKb(null)).isNull();
    }

    @Test
    @DisplayName("null giữ nguyên null — CE và IE không có số đo nào")
    void null_giu_nguyen() {
        assertThat(RuntimeFormatter.roundMs(null)).isNull();
    }

    @Test
    @DisplayName("★ dấu thập phân là dấu chấm, kể cả trên máy dùng locale dấu phẩy")
    void khong_phu_thuoc_locale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("vi-VN"));
            assertThat(RuntimeFormatter.seconds(2030)).isEqualTo("2.03s");
            assertThat(RuntimeFormatter.memory(12_698)).isEqualTo("13 MB");
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
