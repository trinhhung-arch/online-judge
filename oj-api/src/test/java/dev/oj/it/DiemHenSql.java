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

@TestConfiguration
public class DiemHenSql implements BeanPostProcessor {

    public static final Duration TRAN_CHO = Duration.ofSeconds(10);

    private static final Map<Thread, Hen> HEN = new ConcurrentHashMap<>();

    private record Hen(String doanSql, CountDownLatch daToi, BooleanSupplier diTiepKhi) {
    }

    public static void hen(Thread luong, String doanSql, CountDownLatch daToi,
                           BooleanSupplier diTiepKhi) {
        HEN.put(luong, new Hen(doanSql, daToi, diTiepKhi));
    }

    public static void huyHet() {
        HEN.clear();
    }

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
        if (h == null || !sql.contains(h.doanSql()) || !HEN.remove(t, h)) {
            return;
        }
        h.daToi().countDown();
        cho(h.diTiepKhi());
    }

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

    private static Object goi(Object dich, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(dich, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
