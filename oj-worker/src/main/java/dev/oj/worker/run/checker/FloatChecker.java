package dev.oj.worker.run.checker;

import java.math.BigDecimal;

/**
 * So theo token với sai số cho phép trên các token là số.
 *
 * <h2>Sai số tuyệt đối <b>hoặc</b> tương đối — đạt một trong hai là chấp nhận</h2>
 * Chỉ tuyệt đối thì đáp án cỡ {@code 1e9} không bao giờ đạt {@code 1e-6}. Chỉ tương đối thì
 * đáp án {@code 0} không bao giờ đạt, vì mọi sai số quanh 0 đều là 100%. Lấy cái tốt hơn
 * trong hai là quy ước của Codeforces và ICPC, và nó tồn tại vì cả hai lỗi trên đều có thật.
 *
 * <h2>Token không phải số thì so nguyên văn</h2>
 * Đề số thực vẫn có thể in {@code YES} hay {@code -1} xen kẽ. Coi mọi thứ là số rồi
 * {@code parse} hỏng là biến một WA thành một IE.
 *
 * <p>{@code NaN} và {@code Infinity} <b>không</b> bao giờ khớp — {@code Double.parseDouble}
 * nhận cả hai, nên chúng bị loại tường minh ở {@code asNumber}. Ghi rõ ra đây vì đó là loại
 * chi tiết mà một lần "dọn dẹp" sau này sẽ phá mà không ai nhận ra.
 */
public final class FloatChecker implements Checker {

    private final double epsilon;

    public FloatChecker(BigDecimal epsilon) {
        if (epsilon == null || epsilon.signum() < 0) {
            throw new IllegalArgumentException("epsilon phải >= 0, nhận: " + epsilon);
        }
        this.epsilon = epsilon.doubleValue();
    }

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        Tokens want = new Tokens(expected);
        Tokens got = new Tokens(actual);
        while (want.next()) {
            if (!got.next() || !tokenMatches(want, got)) {
                return false;
            }
        }
        return !got.next();
    }

    private boolean tokenMatches(Tokens want, Tokens got) {
        if (want.sameAs(got)) {
            return true;
        }
        Double w = asNumber(want.text());
        Double g = asNumber(got.text());
        if (w == null || g == null) {
            return false;
        }
        double difference = Math.abs(w - g);
        return difference <= epsilon || difference <= epsilon * Math.abs(w);
    }

    private static Double asNumber(String token) {
        try {
            double value = Double.parseDouble(token);
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
