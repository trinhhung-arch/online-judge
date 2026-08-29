package dev.oj.worker.run.checker;

import dev.oj.contract.CheckerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CheckerTest {

    @Nested
    @DisplayName("EXACT")
    class Exact {
        private final Checker checker = Checkers.of(CheckerType.EXACT, null);

        @Test
        @DisplayName("tha thứ khoảng trắng ở CUỐI file, không tha ở giữa")
        void thaCuoiKhongThaGiua() {
            assertThat(match(checker, "1 2 3", "1 2 3\n")).isTrue();
            assertThat(match(checker, "1 2 3", "1 2 3\n\n  ")).isTrue();
            assertThat(match(checker, "1 2 3", "1  2 3")).isFalse();
            assertThat(match(checker, "1 2 3", "1\n2\n3")).isFalse();
        }
    }

    @Nested
    @DisplayName("TOKEN")
    class Token {
        private final Checker checker = Checkers.of(CheckerType.TOKEN, null);

        @Test
        @DisplayName("mọi cách xuống dòng và cách dấu cách đều tương đương")
        void bqKhoangTrang() {
            assertThat(match(checker, "1 2 3", "1\n2\t3\r\n")).isTrue();
            assertThat(match(checker, "1 2 3", "   1   2   3   ")).isTrue();
        }

        @Test
        @DisplayName("thiếu token hoặc thừa token đều là sai")
        void thieuThuaDeuSai() {
            assertThat(match(checker, "1 2 3", "1 2")).isFalse();
            assertThat(match(checker, "1 2 3", "1 2 3 4")).isFalse();
        }

        @Test
        @DisplayName("output rỗng không khớp đáp án có nội dung")
        void rongKhongKhop() {
            assertThat(match(checker, "42", "")).isFalse();
            assertThat(match(checker, "", "")).isTrue();
        }
    }

    @Nested
    @DisplayName("FLOAT")
    class Float {
        private final Checker checker = Checkers.of(CheckerType.FLOAT, new BigDecimal("1e-6"));

        @Test
        @DisplayName("sai số tuyệt đối cứu đáp án quanh 0")
        void quanhKhong() {
            assertThat(match(checker, "0.0", "0.0000001")).isTrue();
            assertThat(match(checker, "0.0", "0.1")).isFalse();
        }

        @Test
        @DisplayName("sai số tương đối cứu đáp án rất lớn")
        void ratLon() {
            assertThat(match(checker, "1000000000.0", "1000000000.0001"))
                    .as("chỉ dùng sai số tuyệt đối thì mọi đáp án cỡ 1e9 đều WA")
                    .isTrue();
        }

        @Test
        @DisplayName("token không phải số thì so nguyên văn, không parse rồi hỏng")
        void tokenChu() {
            assertThat(match(checker, "YES 1.5", "YES 1.5000001")).isTrue();
            assertThat(match(checker, "YES 1.5", "NO 1.5")).isFalse();
        }

        @Test
        @DisplayName("NaN và Infinity không bao giờ khớp")
        void nanVaInf() {
            assertThat(match(checker, "1.0", "NaN")).isFalse();
            assertThat(match(checker, "1.0", "Infinity")).isFalse();
        }
    }

    private static boolean match(Checker checker, String expected, String actual) {
        return checker.matches(expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
