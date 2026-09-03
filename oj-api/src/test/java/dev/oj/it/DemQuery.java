package dev.oj.it;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ★ Bước 6.15 — đếm số câu query trong integration test, để chặn N+1.
 *
 * <h2>Vì sao JDK thuần thay vì {@code datasource-proxy}</h2>
 * {@code nfrplan.md} 2.3 gợi ý thư viện ấy, và nó tốt. Nhưng việc cần làm ở đây đúng bằng một
 * câu: <i>"endpoint này chạy bao nhiêu lần {@code prepareStatement}"</i>. Một
 * {@code java.lang.reflect.Proxy} trả lời được câu đó trong tám mươi dòng, sống hoàn toàn
 * trong {@code src/test}, và không thêm một dependency nào vào một dự án mà mỗi dependency
 * đều phải hỏi người ({@code CLAUDE.md} mục 5.2). Cùng lựa chọn đã chốt cho JWT ở M4.
 *
 * <h2>Nó bắt được cái gì</h2>
 * N+1 là lỗi <b>không có triệu chứng trong test</b>: kết quả đúng, khẳng định xanh, chỉ số
 * query tăng tuyến tính theo số dòng. Nó chỉ lộ ra trên dữ liệu thật, ở đúng trang người dùng
 * mở nhiều nhất. Một khẳng định về <i>số lượng</i> query là cách duy nhất biến nó thành một
 * test đỏ.
 *
 * <pre>
 *   DemQuery.batDau();
 *   var trang = listMySubmissions.thucHien(null, null, 20);
 *   assertThat(DemQuery.dem()).isLessThanOrEqualTo(2);   // KHÔNG phải 21
 * </pre>
 *
 * <h2>Đếm theo LUỒNG, không theo toàn cục</h2>
 * Bộ IT dùng chung một context và Failsafe chạy tuần tự, nhưng reaper, {@code JobRunner} và
 * {@code QueueMetricsSampler} vẫn chạy nền trên luồng lập lịch. Một bộ đếm toàn cục sẽ cộng cả
 * truy vấn của chúng vào, và test đỏ ngẫu nhiên theo nhịp đồng hồ — loại đỏ mà người ta sẽ
 * xoá khẳng định thay vì đi tìm nguyên nhân.
 */
@TestConfiguration
public class DemQuery implements BeanPostProcessor {

    /** Bốn phương thức của {@link Connection} thật sự gửi một câu lệnh xuống database. */
    private static final Set<String> TAO_CAU_LENH =
            Set.of("prepareStatement", "createStatement", "prepareCall", "nativeSQL");

    private static final ThreadLocal<AtomicInteger> BO_DEM = new ThreadLocal<>();

    /** Bắt đầu đếm trên luồng hiện tại. Gọi lại là đặt về 0. */
    public static void batDau() {
        BO_DEM.set(new AtomicInteger());
    }

    /**
     * @return số câu lệnh đã tạo từ {@link #batDau()}
     * @throws IllegalStateException nếu quên gọi {@code batDau()} — im lặng trả 0 ở đây nghĩa
     *         là một khẳng định {@code assertThat(dem()).isLessThan(3)} luôn xanh, tức là một
     *         test không kiểm gì cả mà trông như có
     */
    public static int dem() {
        AtomicInteger c = BO_DEM.get();
        if (c == null) {
            throw new IllegalStateException("Gọi DemQuery.batDau() trước đã");
        }
        return c.get();
    }

    public static void dungDem() {
        BO_DEM.remove();
    }

    private static void ghiNhan() {
        AtomicInteger c = BO_DEM.get();
        if (c != null) {
            c.incrementAndGet();
        }
    }

    /**
     * Bọc mọi bean {@link DataSource} — cả hai pool ({@code app} và {@code judge}).
     *
     * <p>Danh sách interface lấy từ chính lớp đích chứ không viết cứng {@code DataSource.class}:
     * {@code HikariDataSource} còn implement {@link java.io.Closeable}, và Spring gọi
     * {@code close()} lúc huỷ context. Một proxy thiếu interface đó làm pool không bao giờ
     * đóng — vô hại trong test, nhưng là loại rác âm thầm mà một ngày nào đó thành "sao test
     * chạy lâu dần".
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) throws BeansException {
        if (!(bean instanceof DataSource ds)) {
            return bean;
        }
        return Proxy.newProxyInstance(getClass().getClassLoader(),
                bean.getClass().getInterfaces(), new BocDataSource(ds));
    }

    private record BocDataSource(DataSource that) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object ket = goi(that, method, args);
            if (ket instanceof Connection con && method.getName().equals("getConnection")) {
                return Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{Connection.class}, new BocConnection(con));
            }
            return ket;
        }
    }

    private record BocConnection(Connection that) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (TAO_CAU_LENH.contains(method.getName())) {
                ghiNhan();
            }
            return goi(that, method, args);
        }
    }

    /**
     * Gỡ {@link InvocationTargetException} ra trước khi ném lại.
     *
     * <p>Không có dòng này thì mọi {@code SQLException} của driver tới tay Spring dưới lớp bọc
     * của reflection, và {@code SQLExceptionTranslator} không dịch được nó — một lỗi ràng buộc
     * khoá ngoại sẽ hiện ra thành {@code UndeclaredThrowableException} thay vì
     * {@code DataIntegrityViolationException}. Test sẽ đỏ vì đúng lý do nhưng với sai thông báo.
     */
    private static Object goi(Object dich, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(dich, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
