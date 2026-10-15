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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

/**
 * ★ Điểm hẹn ngay trước một câu SQL, trên một luồng chỉ định — để dàn cảnh race trong IT.
 *
 * <h2>Vì sao không dùng {@code @MockitoSpyBean} như {@code KillApiDuringSubmitIT}</h2>
 * Mỗi tổ hợp bean bị thay là <b>một context Spring riêng</b>, và context nào cũng giữ hai pool
 * — 20 + 6 connection. Postgres của bộ IT có {@code max_connections} mặc định (100): thêm một
 * context nữa thì lớp IT chạy <i>sau</i> nó đỏ với {@code FATAL: sorry, too many clients
 * already} — một lớp không liên quan gì, đỏ vì một lớp khác vừa được thêm. Đã gặp đúng như vậy
 * khi viết {@link BangXepHangGhiSongSongIT}. Lớp này sống trong context dùng chung, giống
 * {@link DemQuery}, nên không đẻ thêm context nào.
 *
 * <h2>Cách dùng</h2>
 * <pre>
 *   DiemHenSql.hen(luongB, "AND user_id IN (", bDaToi, () -> aXong.getCount() == 0);
 * </pre>
 * Ở câu lệnh <b>đầu tiên</b> có chứa đoạn SQL ấy, luồng B dừng ngay trước khi chuẩn bị câu
 * lệnh: đếm ngược {@code bDaToi}, rồi chờ tới khi điều kiện đúng. Ở mức READ COMMITTED câu
 * lệnh chụp ảnh dữ liệu lúc nó <i>chạy</i>, tức là sau điểm hẹn — nên nó thấy mọi thứ luồng
 * kia đã commit trong lúc nó chờ. Mọi luồng khác, kể cả luồng lập lịch nền, đi qua bình thường.
 *
 * <p>Hẹn mà không ai tới — đoạn SQL bị viết lại chẳng hạn — thì luồng chờ {@code bDaToi} hết
 * giờ và test đỏ. Không có đường nào để một điểm hẹn trượt mà test vẫn xanh.
 */
@TestConfiguration
public class DiemHenSql implements BeanPostProcessor {

    /** Trần chờ ở mọi điểm hẹn — đủ rộng cho máy CI chậm, đủ hẹp để một test treo lộ ra. */
    public static final Duration TRAN_CHO = Duration.ofSeconds(10);

    private static final Map<Thread, Hen> HEN = new ConcurrentHashMap<>();

    private record Hen(String doanSql, CountDownLatch daToi, BooleanSupplier diTiepKhi) {
    }

    /** Đặt một điểm hẹn cho {@code luong}. Mỗi luồng tối đa một hẹn; hẹn chỉ dùng một lần. */
    public static void hen(Thread luong, String doanSql, CountDownLatch daToi,
                           BooleanSupplier diTiepKhi) {
        HEN.put(luong, new Hen(doanSql, daToi, diTiepKhi));
    }

    /** Gọi ở {@code @AfterEach}: một hẹn không ai tới không được rò sang test sau. */
    public static void huyHet() {
        HEN.clear();
    }

    /** Chờ tới khi điều kiện đúng, tối đa {@link #TRAN_CHO}; quá giờ thì ném. */
    public static void cho(BooleanSupplier dieuKien) {
        long hetHan = System.nanoTime() + TRAN_CHO.toNanos();
        while (!dieuKien.getAsBoolean()) {
            if (System.nanoTime() > hetHan) {
                throw new AssertionError("hết " + TRAN_CHO + " mà điều kiện đi tiếp chưa tới");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static void toiCauLenh(String sql) {
        Thread t = Thread.currentThread();
        Hen h = HEN.get(t);
        // remove(t, h) trước khi chờ: điều kiện đi tiếp có thể tự chạy SQL trên chính luồng
        // này (hỏi pg_locks chẳng hạn), và câu ấy không được rơi lại vào điểm hẹn.
        if (h == null || !sql.contains(h.doanSql()) || !HEN.remove(t, h)) {
            return;
        }
        h.daToi().countDown();
        cho(h.diTiepKhi());
    }

    /** Bọc mọi bean {@link DataSource}, như {@link DemQuery} — xem javadoc ở đó về interface. */
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
            if ((method.getName().equals("prepareStatement") || method.getName().equals("prepareCall"))
                    && args != null && args.length > 0 && args[0] instanceof String sql) {
                toiCauLenh(sql);
            }
            return goi(that, method, args);
        }
    }

    /** Gỡ {@link InvocationTargetException} — cùng lý do với {@code DemQuery.goi}. */
    private static Object goi(Object dich, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(dich, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
