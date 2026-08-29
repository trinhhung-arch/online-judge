package dev.oj.platform.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Use-case này gọi được <b>không cần đăng nhập</b>. Bước 4.6.
 *
 * <h2>Vì sao phải khai báo tường minh thay vì cứ để trống</h2>
 * Vì "không có annotation" và "cố ý công khai" trông giống hệt nhau khi đọc mã, mà hậu quả
 * thì ngược nhau hoàn toàn. LUẬT 8 bắt mọi use-case chọn một trong ba khả năng, nên một
 * use-case công khai <i>vì có người quyết định thế</i> phân biệt được với một use-case công
 * khai <i>vì có người quên</i>.
 *
 * <p>Và nó cho một thứ đáng giá hơn: <b>một lệnh {@code grep} liệt kê đầy đủ mọi lối vào
 * không cần xác thực của cả hệ thống</b>. Danh sách đó là thứ đầu tiên phải đọc trong buổi
 * tấn công chéo ở tuần 9.
 *
 * <p>Một use-case công khai vẫn phải tự lọc dữ liệu: đề chưa xuất bản, bài nộp của người
 * khác, testcase ẩn. {@code PublicAccess} nói <i>"khách gọi được"</i>, không nói
 * <i>"khách thấy được mọi thứ"</i>.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PublicAccess {

    /** Bắt buộc: vì sao use-case này không cần đăng nhập. Người rà soát sẽ đọc đúng câu này. */
    String value();
}
