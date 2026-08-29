package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.api.dto.SessionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bất biến #9 ở tầng {@code api}: <b>không DTO nào tự in mật khẩu hay token ra log</b>.
 *
 * <h2>Vì sao là một test bằng phản chiếu chứ không phải ba câu assert viết tay</h2>
 * Vì thứ cần bảo vệ là <i>tương lai</i>. Ai đó sẽ thêm một record mới vào {@code AuthRequests}
 * — {@code ResetPassword}, {@code VerifyEmail}, {@code ApiKey} — và record Java sinh sẵn
 * {@code toString()} in mọi trường. Một test liệt kê sẵn ba class chỉ bảo vệ ba class đó;
 * test này duyệt <b>mọi</b> record trong file và bắt cả những cái chưa được viết.
 *
 * <p>Nó cũng chính là bài học của {@code SubmissionEvent} ở M3, áp lại cho một họ class khác.
 */
class AuthRequestsTest {

    /** Giá trị mồi: nếu chuỗi này xuất hiện trong {@code toString()} thì trường đã bị lộ. */
    private static final String BI_MAT = "MAT-KHAU-KHONG-DUOC-VAO-LOG-9f3a";

    @Test
    @DisplayName("★ mọi record trong AuthRequests đều không in trường bí mật")
    void khong_record_nao_lo_bi_mat() throws Exception {
        var records = List.of(
                new AuthRequests.Register("nguoi", "a@oj.test", "Người", BI_MAT),
                new AuthRequests.Login("nguoi", BI_MAT),
                new AuthRequests.Refresh(BI_MAT),
                new AuthRequests.ChangePassword(BI_MAT, BI_MAT));

        // Nếu ai đó thêm record mới mà quên đưa vào danh sách trên thì vế dưới đỏ.
        assertThat(AuthRequests.class.getDeclaredClasses())
                .describedAs("thêm record mới thì thêm một dòng vào danh sách kiểm ở trên")
                .hasSize(records.size());

        for (Object dto : records) {
            assertThat(dto.toString())
                    .describedAs("%s in ra bí mật", dto.getClass().getSimpleName())
                    .doesNotContain(BI_MAT);
        }
    }

    @Test
    @DisplayName("★ SessionResponse cũng vậy — nó mang CẢ HAI token")
    void session_response_khong_lo_token() {
        var r = new SessionResponse(BI_MAT, BI_MAT, 900, 1L, "dev", "USER");

        assertThat(r.toString()).doesNotContain(BI_MAT);
        // ...nhưng vẫn phải nói được nó là của ai, nếu không thì log vô dụng.
        assertThat(r.toString()).contains("dev");
    }

    @Test
    @DisplayName("tên trường mật khẩu không lọt vào toString dưới dạng nào khác")
    void khong_lo_qua_ten_truong() {
        String s = new AuthRequests.Register("nguoi", "a@oj.test", "Người", BI_MAT)
                .toString().toLowerCase(Locale.ROOT);

        assertThat(s).doesNotContain("password", "matkhau", "mat_khau");
    }
}
