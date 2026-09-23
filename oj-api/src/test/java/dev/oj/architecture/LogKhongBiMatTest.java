package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ LUẬT 10 · bất biến #9 ở DÒNG LOG, không chỉ ở {@code toString()} (bao-mat-plan Lỗ 3).
 *
 * <p>Năm chỗ ép {@code toString()} không chứa bí mật chặn được {@code log.info("{}", cmd)}. Chúng
 * KHÔNG chặn {@code log.info("source={}", cmd.source())} — và trong 12 bất biến, #9 là cái duy
 * nhất chỉ có javadoc canh. Luật này quét mọi lời gọi {@code log.*(…)} của cả {@code oj-api} lẫn
 * {@code oj-worker}: bỏ chuỗi literal đi, phần còn lại (các THAM SỐ) không được nhắc tới một tên
 * mang bí mật. ArchUnit không làm được việc này — nó thấy lời gọi, không thấy đối số.
 *
 * <p>Là lưới thứ nhất, bắt kiểu viết. Lưới thứ hai — log THẬT trên đường nộp bài và đăng nhập —
 * là {@code LogKhongLoBiMatIT}.
 *
 * <p>⚠️ <b>Giới hạn đã đo (2026-09-24):</b> luật chỉ nhận ra dạng {@code log.x(…)} — trường
 * {@code log} là quy ước của cả repo. Một dòng viết
 * {@code LoggerFactory.getLogger(…).warn("{}", matKhau)} lọt qua luật này; chỉ IT bắt được nó
 * (gỡ-cơ-chế L2). Đừng coi xanh ở đây là đủ cho bất biến #9 — hai lưới cùng xanh mới là đủ.
 */
class LogKhongBiMatTest {

    private static final List<Path> GOC = List.of(
            Path.of("src", "main", "java"),
            Path.of("..", "oj-worker", "src", "main", "java"));

    /**
     * Tên mang bí mật. Không có {@code code}/{@code ma}: chúng quá chung ({@code e.code()} là mã
     * lỗi như {@code identity.khoa_tam}) — bắt nhầm bốn dòng vô hại ở lần chạy đầu 2026-09-24.
     */
    private static final Pattern NHAY = Pattern.compile("\\b(source|sourceCode|maNguon|password|matKhau"
            + "|matKhauMoi|matKhauCu|secret|secretEnc|biMat|token|tokenTho|accessToken|refreshToken"
            + "|rawCode|noiDung|input|output|expected|maHaiLop|compileLog)\\b");

    private static final Pattern LOG = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\s*\\(");

    @Test
    @DisplayName("★ không lời gọi log.* nào có THAM SỐ mang tên bí mật (source, mật khẩu, token, testcase…)")
    void log_khong_nhan_bi_mat() throws IOException {
        List<String> vi = new ArrayList<>();
        int soLoiGoi = 0;
        for (Path goc : GOC) {
            for (Path f : tepJava(goc)) {
                String s = Files.readString(f);
                Matcher m = LOG.matcher(s);
                while (m.find()) {
                    soLoiGoi++;
                    String thamSo = boChuoi(thanLoiGoi(s, m.end()));
                    Matcher n = NHAY.matcher(thamSo);
                    if (n.find()) {
                        int dong = s.substring(0, m.start()).split("\n", -1).length;
                        vi.add(goc.relativize(f) + ":" + dong + " → " + n.group(1));
                    }
                }
            }
        }
        assertThat(soLoiGoi).as("quét không thấy lời gọi log nào — đường dẫn hỏng?").isGreaterThan(100);
        assertThat(vi).as("bất biến #9: đưa giá trị vào log là rò rỉ; log độ dài/băm/id thay vì giá trị")
                .isEmpty();
    }

    /** Phần giữa hai dấu ngoặc của lời gọi, đếm ngoặc và bỏ qua ngoặc nằm trong chuỗi. */
    private static String thanLoiGoi(String s, int tu) {
        int sau = 1;
        int i = tu;
        while (i < s.length() && sau > 0) {
            char c = s.charAt(i);
            if (c == '"') {
                i++;
                while (i < s.length() && s.charAt(i) != '"') {
                    i += s.charAt(i) == '\\' ? 2 : 1;
                }
            } else if (c == '(') {
                sau++;
            } else if (c == ')') {
                sau--;
            }
            i++;
        }
        return s.substring(tu, Math.max(tu, i - 1));
    }

    private static String boChuoi(String s) {
        return s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    private static List<Path> tepJava(Path goc) throws IOException {
        try (Stream<Path> st = Files.walk(goc)) {
            return st.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }
}
