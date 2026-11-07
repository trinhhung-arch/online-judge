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

@TestConfiguration
public class DemQuery implements BeanPostProcessor {

    private static final Set<String> TAO_CAU_LENH =
            Set.of("prepareStatement", "createStatement", "prepareCall", "nativeSQL");

    private static final ThreadLocal<AtomicInteger> BO_DEM = new ThreadLocal<>();

    public static void batDau() {
        BO_DEM.set(new AtomicInteger());
    }

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

    private static Object goi(Object dich, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(dich, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
