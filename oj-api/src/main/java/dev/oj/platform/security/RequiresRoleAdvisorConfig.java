package dev.oj.platform.security;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * Ép {@link RequiresRole} lúc chạy — Bước 4.6.
 *
 * <h2>Vì sao là {@code Advisor} thủ công chứ không phải {@code @Aspect}</h2>
 * {@code @Aspect} cần {@code spring-boot-starter-aop}, kéo theo {@code aspectjweaver} — một
 * dependency mới cho đúng một chốt kiểm, mà thêm dependency là việc phải hỏi người
 * ({@code CLAUDE.md} mục 5.2). {@code spring-aop} thì <b>đã</b> có sẵn: nó đi cùng
 * {@code spring-context}, và cùng cơ chế này đang chạy cho {@code @Transactional}.
 *
 * <h2>★ {@code @Role(ROLE_INFRASTRUCTURE)} KHÔNG phải trang trí</h2>
 * Không có {@code aspectjweaver}, Spring Boot đăng ký {@code InfrastructureAdvisorAutoProxyCreator}
 * — và class đó <b>chỉ nhìn những {@code Advisor} có role hạ tầng</b>. Thiếu annotation ấy thì
 * bean dưới đây được tạo ra, không báo lỗi gì, và <b>không chặn một lời gọi nào</b>. Đó là
 * kiểu hỏng tệ nhất có thể có ở một chốt bảo mật: im lặng và trông như đang hoạt động.
 *
 * <p>Vì kiểu hỏng đó im lặng, nó được bắt bằng một integration test thật —
 * {@code RequiresRoleIT} gọi một use-case ADMIN bằng vai trò USER và đòi 403. Test đó tồn tại
 * để phát hiện đúng trường hợp proxy không được gắn.
 *
 * <h2>Tên class có hậu tố {@code Config} vì Spring bắt phải thế</h2>
 * Một class {@code @Configuration} tự đăng ký thành bean mang tên chính nó viết thường đầu —
 * {@code requiresRoleAdvisor}. Nếu phương thức {@code @Bean} bên trong cũng tên như vậy thì
 * hai định nghĩa đâm nhau và ứng dụng <b>không khởi động được</b>:
 * <i>"A bean with that name has already been defined"</i>. Đây là một va chạm im lặng lúc
 * biên dịch và ồn ào lúc chạy — kiểu tốt hơn kiểu ngược lại, nhưng vẫn tốn một lượt chạy test
 * để nhìn ra.
 *
 * <h2>Chạy TRƯỚC advisor của transaction</h2>
 * {@link Ordered#HIGHEST_PRECEDENCE}. Một request sắp bị từ chối không có lý do gì được mở
 * transaction trước: pool app chỉ có 20 connection, và mở rồi rollback là trả tiền cho một
 * việc chắc chắn không dùng tới.
 */
@Configuration
public class RequiresRoleAdvisorConfig {

    /**
     * {@code @Role} ở đây là {@code org.springframework.context.annotation.Role} — viết đầy đủ
     * vì trong package này {@code Role} là {@link dev.oj.platform.security.Role}, vai trò
     * người dùng. Hai khái niệm hoàn toàn khác nhau, và import nhầm thì trình biên dịch báo
     * một câu khó hiểu.
     */
    @Bean
    @org.springframework.context.annotation.Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    public Advisor requiresRoleAdvisor(CurrentUserProvider currentUser) {
        var pointcut = new AnnotationMatchingPointcut(RequiresRole.class, true);
        var advisor = new DefaultPointcutAdvisor(pointcut, kiemQuyen(currentUser));
        advisor.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return advisor;
    }

    private static MethodInterceptor kiemQuyen(CurrentUserProvider currentUser) {
        return invocation -> {
            Method method = invocation.getMethod();
            // equals/hashCode/toString cũng đi qua proxy. Bắt chúng ném "chưa đăng nhập" sẽ
            // làm một dòng log vô hại thành một ngoại lệ khó hiểu.
            if (method.getDeclaringClass() != Object.class) {
                RequiresRole yeuCau = AnnotationUtils.findAnnotation(
                        invocation.getThis().getClass(), RequiresRole.class);
                if (yeuCau != null) {
                    kiem(currentUser, yeuCau.value());
                }
            }
            return invocation.proceed();
        };
    }

    /**
     * Ném {@code UNAUTHENTICATED} nếu chưa đăng nhập (401), {@code FORBIDDEN} nếu vai trò
     * không đủ (403).
     *
     * <p><b>403 chứ không phải 200 rỗng</b> — đây là chữ của Bước 4.8 và của bảng test bắt
     * buộc trong {@code CLAUDE.md} mục 6. Trả về một danh sách rỗng cho người không có quyền
     * là nói dối họ rằng không có dữ liệu, và người viết frontend sẽ tin.
     */
    private static void kiem(CurrentUserProvider currentUser, Role canCo) {
        var nguoiGoi = currentUser.current();   // ném 401 nếu không có danh tính
        if (!nguoiGoi.role().atLeast(canCo)) {
            throw AuthorizationException.thieuQuyen(canCo, nguoiGoi.role());
        }
    }
}
