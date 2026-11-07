package dev.oj.platform.error;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JsonHongKhongLoVaoLogTest {

    private static final String MOC = "MOC_BI_MAT_5e81c4";

    record DangNhap(String dinhDanh, String password) {
    }

    enum VaiTro { USER, ADMIN }

    record DoiVaiTro(VaiTro vaiTro) {
    }

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final ListAppender<ILoggingEvent> nghe = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @BeforeEach
    void ganTai() {
        nghe.start();
        logger.addAppender(nghe);
    }

    @AfterEach
    void goTai() {
        logger.detachAppender(nghe);
    }

    private static HttpMessageNotReadableException docHong(Class<?> kieu, String than) {
        var input = new MockHttpInputMessage(than.getBytes(StandardCharsets.UTF_8));
        HttpMessageNotReadableException e = catchThrowableOfType(HttpMessageNotReadableException.class,
                () -> new JacksonJsonHttpMessageConverter().read(kieu, input));
        assertThat(e).as("bộ chuyển đổi phải từ chối thân này").isNotNull();
        return e;
    }

    private String dongLogDuyNhat() {
        assertThat(nghe.list).as("handleUnreadable phải ghi đúng một dòng").hasSize(1);
        return nghe.list.getFirst().getFormattedMessage();
    }

    @Test
    @DisplayName("★ mật khẩu viết thiếu ngoặc kép không vào log — dù câu lỗi của Jackson và của Spring đều chứa nó")
    void mat_khau_thieu_ngoac_kep_khong_vao_log() {
        HttpMessageNotReadableException e = docHong(DangNhap.class,
                "{\"dinhDanh\":\"dev\",\"password\":" + MOC + "}");
        assertThat(e.getMessage()).as("tiền đề: câu lỗi của Spring chép nguyên token lạ").contains(MOC);

        ResponseEntity<ApiError> res = handler.handleUnreadable(e);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().code()).isEqualTo("request.malformed");
        assertThat(dongLogDuyNhat()).doesNotContain(MOC)
                .as("vẫn phải đủ để gỡ lỗi: loại lỗi và vị trí")
                .contains("StreamReadException").containsPattern("tại (dòng|byte|ký tự) \\d+");
    }

    @Test
    @DisplayName("★ giá trị enum sai không vào log; tên TRƯỜNG hỏng thì có — nó là của ta, không của người gửi")
    void gia_tri_enum_sai_khong_vao_log() {
        HttpMessageNotReadableException e = docHong(DoiVaiTro.class,
                "{\"vaiTro\":\"SUPERADMIN_" + MOC + "\"}");
        assertThat(e.getMessage()).as("tiền đề: câu lỗi của Spring chép nguyên giá trị sai").contains(MOC);

        handler.handleUnreadable(e);

        assertThat(dongLogDuyNhat()).doesNotContain(MOC).contains("ở trường .vaiTro");
    }

    @Test
    @DisplayName("lỗi Jackson bọc một lỗi khác: không in câu của lỗi bên trong; tên trường lấy từ lỗi Jackson, cắt ở 80 ký tự")
    void loi_boc_trong_khong_in_cau_va_cat_duong() {
        String khoaDai = "k".repeat(MoTaLoiAnToan.TRAN_DUONG_TRUONG * 2);
        var boc = tools.jackson.core.JacksonException.wrapWithPath(
                new IllegalArgumentException("câu chứa " + MOC), new Object(), khoaDai);

        String moTa = MoTaLoiAnToan.cua(boc);

        assertThat(moTa).startsWith("IllegalArgumentException ở trường .k").doesNotContain(MOC)
                .endsWith("…").hasSizeLessThan("IllegalArgumentException ở trường .".length()
                        + MoTaLoiAnToan.TRAN_DUONG_TRUONG + 2);
    }

    @Test
    @DisplayName("thiếu thân request (không có nguyên nhân nào bên dưới) → chỉ tên lớp, không chép câu nào")
    void thieu_than_chi_con_ten_lop() {
        var e = new HttpMessageNotReadableException("Required request body is missing: " + MOC,
                new MockHttpInputMessage(new byte[0]));

        assertThat(MoTaLoiAnToan.cua(e)).isEqualTo("HttpMessageNotReadableException");
    }
}
