package dev.oj.worker.run.checker;

/**
 * So từng byte — <b>trừ khoảng trắng ở cuối file</b>.
 *
 * <h2>Vì sao "exact" vẫn tha thứ cái đuôi</h2>
 * Vì thứ duy nhất nó bắt được nếu không tha là chuyện thí sinh có gõ {@code endl} ở dòng cuối
 * hay không — một chi tiết không liên quan gì tới thuật toán, mà lại phân biệt đối xử theo
 * ngôn ngữ: {@code print()} của Python luôn thêm {@code \n}, {@code std::cout} thì không.
 * Mọi OJ nghiêm túc đều tha cái đuôi này, và đây là chỗ ghi lại lý do để không ai "sửa" nó.
 *
 * <p>Khoảng trắng ở <i>giữa</i> thì vẫn tính: đó chính là điểm khác nhau giữa
 * {@code EXACT} và {@code TOKEN}.
 */
public final class ExactChecker implements Checker {

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        int e = trimmedLength(expected);
        int a = trimmedLength(actual);
        if (e != a) {
            return false;
        }
        for (int i = 0; i < e; i++) {
            if (expected[i] != actual[i]) {
                return false;
            }
        }
        return true;
    }

    private static int trimmedLength(byte[] data) {
        int length = data.length;
        while (length > 0 && Tokens.isSpace(data[length - 1])) {
            length--;
        }
        return length;
    }
}
