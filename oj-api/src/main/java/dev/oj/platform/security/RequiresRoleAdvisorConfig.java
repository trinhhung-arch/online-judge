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

@Configuration
public class RequiresRoleAdvisorConfig {

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

    private static void kiem(CurrentUserProvider currentUser, Role canCo) {
        var nguoiGoi = currentUser.current();
        if (nguoiGoi.role().atLeast(canCo)) {
            return;
        }

        if (canCo == Role.ADMIN && nguoiGoi.adminChuaBatHaiLop()) {
            throw AuthorizationException.canHaiLop();
        }
        throw AuthorizationException.thieuQuyen(canCo, nguoiGoi.role());
    }
}
