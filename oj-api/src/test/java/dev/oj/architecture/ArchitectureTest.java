package dev.oj.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(
        packages = "dev.oj",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final List<String> MODULES =
            List.of("platform", "identity", "problems", "judging", "contests", "ai");

    private static final String[] FRAMEWORK = {
            "org.springframework..",
            "jakarta.persistence..", "jakarta.transaction..", "jakarta.servlet..",
            "com.fasterxml.jackson..",
            "org.hibernate..",
            "java.sql..", "javax.sql..",
            "com.rabbitmq..", "com.zaxxer.hikari..",
            "io.lettuce..", "org.redisson..",
            "io.minio.."
    };

    private static final boolean APPLICATION_DUOC_THAY_INFRASTRUCTURE = false;

    private static final DescribedPredicate<JavaClass> NGOAI_JDK =
            new DescribedPredicate<>("nằm ngoài JDK và ngoài chính oj-contract") {
                @Override
                public boolean test(JavaClass clazz) {
                    String ten = clazz.getBaseComponentType().getName();
                    if (ten.indexOf('.') < 0) {
                        return false;
                    }
                    return !(ten.startsWith("java.") || ten.startsWith("dev.oj.contract."));
                }
            };

    @ArchTest
    static final ArchRule luat1_domain_khong_biet_framework =
            noClasses()
                    .that().resideInAPackage("dev.oj..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK)
                    .because("domain là Java thuần: bất biến nghiệp vụ phải test được bằng JUnit trần, "
                            + "không Spring context, chạy dưới 1 giây (CLAUDE.md mục 3 luật 1)");

    @ArchTest
    static final ArchRule luat2_infrastructure_la_rieng_tu =
            classes()
                    .that().resideInAPackage("dev.oj..")
                    .should(khongChamInfrastructureCuaModuleKhac())
                    .because("infrastructure là cách một module tự cài đặt mình. Module khác chạm vào "
                            + "nghĩa là đổi một câu SQL ở judging làm vỡ contests (CLAUDE.md mục 3 luật 2)");

    @ArchTest
    static final ArchRule luat2b_domain_khong_nhin_ra_ngoai =
            noClasses()
                    .that().resideInAPackage("dev.oj.*.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "dev.oj.*.application..", "dev.oj.*.infrastructure..", "dev.oj.*.api..")
                    .because("domain nằm ở đáy. Nó không biết ai gọi nó và không biết dữ liệu lưu ở đâu");

    @ArchTest
    static final ArchRule luat2c_application_khong_nhin_ra_ngoai =
            APPLICATION_DUOC_THAY_INFRASTRUCTURE
                    ? noClasses()
                            .that().resideInAPackage("dev.oj.*.application..")
                            .should().dependOnClassesThat().resideInAnyPackage("dev.oj.*.api..")
                            .because("use-case không biết HTTP tồn tại")
                    : noClasses()
                            .that().resideInAPackage("dev.oj.*.application..")
                            .should().dependOnClassesThat().resideInAnyPackage(
                                    "dev.oj.*.api..", "dev.oj.*.infrastructure..")
                            .because("use-case định nghĩa port và test bằng fake repository; "
                                    + "nó không biết HTTP lẫn JdbcClient tồn tại "
                                    + "(xem ghi chú APPLICATION_DUOC_THAY_INFRASTRUCTURE)");

    @ArchTest
    static final ArchRule luat3_chieu_module_mot_chieu =
            layeredArchitecture().consideringOnlyDependenciesInAnyPackage("dev.oj..")
                    .withOptionalLayers(true)

                    .layer("app").definedBy("dev.oj")
                    .layer("platform").definedBy("dev.oj.platform..")
                    .layer("identity").definedBy("dev.oj.identity..")
                    .layer("problems").definedBy("dev.oj.problems..")
                    .layer("judging").definedBy("dev.oj.judging..")
                    .layer("contests").definedBy("dev.oj.contests..")
                    .layer("ai").definedBy("dev.oj.ai..")

                    .whereLayer("contests").mayOnlyBeAccessedByLayers("app")
                    .whereLayer("ai").mayOnlyBeAccessedByLayers("app")
                    .whereLayer("judging").mayOnlyBeAccessedByLayers("contests", "ai", "app")
                    .whereLayer("problems").mayOnlyBeAccessedByLayers("judging", "contests", "ai", "app")
                    .whereLayer("identity").mayOnlyBeAccessedByLayers("problems", "judging", "contests", "ai", "app")

                    .because("problems không biết judging tồn tại. Một chiều ngược lọt vào là "
                            + "hai module dính liền vĩnh viễn (CLAUDE.md mục 3 luật 3)");

    @ArchTest
    static final ArchRule luat3b_platform_khong_biet_nghiep_vu =
            noClasses()
                    .that().resideInAPackage("dev.oj.platform..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "dev.oj.identity..", "dev.oj.problems..",
                            "dev.oj.judging..", "dev.oj.contests..", "dev.oj.ai..")
                    .because("platform chứa config, error, security, trace — nếu nó biết judging thì "
                            + "nó không còn là nền, nó là một module nghiệp vụ thứ bảy");

    @ArchTest
    static final ArchRule luat4_contract_chi_biet_jdk =
            noClasses()
                    .that().resideInAPackage("dev.oj.contract..")
                    .should().dependOnClassesThat(NGOAI_JDK)
                    .because("oj-contract được đóng băng ở tuần 1 và dùng chung bởi oj-api lẫn oj-worker. "
                            + "Một annotation Jackson lọt vào đây là bắt đầu của việc worker phải biết "
                            + "hạ tầng của API (CLAUDE.md mục 3 luật 4)");

    private static String moduleCua(JavaClass clazz) {
        String pkg = clazz.getPackageName();
        for (String m : MODULES) {
            if (pkg.equals("dev.oj." + m) || pkg.startsWith("dev.oj." + m + ".")) {
                return m;
            }
        }
        return null;
    }

    private static ArchCondition<JavaClass> khongChamInfrastructureCuaModuleKhac() {
        return new ArchCondition<>("không phụ thuộc vào infrastructure của module khác") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String tu = moduleCua(item);
                if (tu == null) {
                    return;
                }
                for (Dependency phuThuoc : item.getDirectDependenciesFromSelf()) {
                    String den = moduleCua(phuThuoc.getTargetClass());
                    if (den == null || den.equals(tu)) {
                        continue;
                    }
                    String pkgDich = phuThuoc.getTargetClass().getPackageName();
                    if (pkgDich.startsWith("dev.oj." + den + ".infrastructure")) {
                        events.add(SimpleConditionEvent.violated(
                                phuThuoc,
                                "module '" + tu + "' chạm infrastructure của '" + den + "': "
                                        + phuThuoc.getDescription()));
                    }
                }
            }
        };
    }

}
