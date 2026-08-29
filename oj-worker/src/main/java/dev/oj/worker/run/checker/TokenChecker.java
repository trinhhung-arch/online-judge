package dev.oj.worker.run.checker;

/**
 * So theo dãy token, bỏ qua mọi khác biệt về khoảng trắng. Mặc định của bảng {@code problems}.
 *
 * <p>Đây là checker đúng cho gần hết bài tập: đề hiếm khi quan tâm thí sinh xuống dòng hay
 * cách dấu cách, và bắt bẻ chuyện đó chỉ tạo ra những lần WA mà không ai học được gì.
 */
public final class TokenChecker implements Checker {

    @Override
    public boolean matches(byte[] expected, byte[] actual) {
        Tokens want = new Tokens(expected);
        Tokens got = new Tokens(actual);
        while (want.next()) {
            if (!got.next() || !want.sameAs(got)) {
                return false;
            }
        }
        return !got.next();     // output thừa token cũng là sai
    }
}
