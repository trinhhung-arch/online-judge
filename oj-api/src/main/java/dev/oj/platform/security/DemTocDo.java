package dev.oj.platform.security;

import java.time.Duration;

/**
 * Đếm số lượt của một khoá trong một cửa sổ thời gian — hạ tầng của {@link GioiHanApiFilter}.
 *
 * <p>Tách thành cổng vì hai lý do. Thứ nhất, bộ lọc là chỗ mọi request đi qua, nên nó phải
 * test được mà không cần Redis thật. Thứ hai, Redis ở đây là <b>lựa chọn</b>, không phải điều
 * kiện: đếm được bằng gì cũng xong, miễn là nhiều instance API cùng thấy một con số.
 */
public interface DemTocDo {

    /**
     * @return số lượt của {@code khoa} tính cả lượt này, trong cửa sổ hiện tại
     * @throws RuntimeException khi không đếm được — <b>nơi gọi quyết định</b> cho qua hay chặn.
     *         Cổng này cố ý không tự nuốt lỗi: nuốt ở đây là mọi nơi gọi đều mất quyền chọn
     */
    long tang(String khoa, Duration cuaSo);
}
