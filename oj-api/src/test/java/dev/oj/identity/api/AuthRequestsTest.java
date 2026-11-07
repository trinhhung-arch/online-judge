package dev.oj.identity.api;

import dev.oj.identity.api.dto.AuthRequests;
import dev.oj.identity.api.dto.SessionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRequestsTest {

    private static final String BI_MAT = "MAT-KHAU-KHONG-DUOC-VAO-LOG-9f3a";

    @Test
    @DisplayName("★ mọi record trong AuthRequests đều không in trường bí mật")
    void khong_record_nao_lo_bi_mat() throws Exception {
        var records = List.of(
                new AuthRequests.Register("nguoi", "a@oj.test", "Người", BI_MAT, BI_MAT),
                new AuthRequests.Login("nguoi", BI_MAT, BI_MAT),
                new AuthRequests.XacNhanHaiLop(BI_MAT),
                new AuthRequests.TatHaiLop(BI_MAT, BI_MAT),
                new AuthRequests.XacMinhEmail(BI_MAT),
                new AuthRequests.Refresh(BI_MAT),
                new AuthRequests.ChangePassword(BI_MAT, BI_MAT));

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
        assertThat(r.toString()).contains("dev");
    }

    @Test
    @DisplayName("tên trường mật khẩu không lọt vào toString dưới dạng nào khác")
    void khong_lo_qua_ten_truong() {
        String s = new AuthRequests.Register("nguoi", "a@oj.test", "Người", BI_MAT, BI_MAT)
                .toString().toLowerCase(Locale.ROOT);

        assertThat(s).doesNotContain("password", "matkhau", "mat_khau");
    }
}
