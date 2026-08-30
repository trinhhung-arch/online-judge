package dev.oj.contests.domain;

import java.util.List;

/**
 * Tra thể thức theo mã — <b>chỗ duy nhất</b> cần sửa khi thêm một thể thức mới.
 *
 * <p>NFR M4 nói <i>"thêm một thể thức = 1 file"</i>. Chính xác hơn: một file mới, cộng một
 * dòng ở đây, cộng một giá trị trong {@code CHECK (format IN (...))} của một migration mới.
 * Ba chỗ, và cả ba đều là nơi mà quên sẽ hỏng <b>ồn ào</b> — trình biên dịch, hoặc
 * {@link #tuMa}, hoặc database. Không có chỗ nào quên mà im lặng.
 */
public final class ContestFormats {

    private static final List<ContestFormat> TAT_CA = List.of(new IcpcFormat(), new IoiFormat());

    private ContestFormats() {
    }

    public static ContestFormat tuMa(String code) {
        for (ContestFormat f : TAT_CA) {
            if (f.code().equals(code)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Thể thức không hợp lệ: " + code);
    }

    public static List<String> maHopLe() {
        return TAT_CA.stream().map(ContestFormat::code).toList();
    }
}
