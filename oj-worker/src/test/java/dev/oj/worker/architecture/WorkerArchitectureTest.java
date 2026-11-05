package dev.oj.worker.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "dev.oj.worker",
        importOptions = ImportOption.DoNotIncludeTests.class)
class WorkerArchitectureTest {

    @ArchTest
    static final ArchRule luat6_chi_sandbox_duoc_spawn_tien_trinh =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.sandbox..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                    .because("mọi thực thi mã người dùng đi qua isolate, kể cả bước biên dịch "
                            + "(bất biến #4). ProcessBuilder + timeout không phải sandbox");

    @ArchTest
    static final ArchRule luat7_chi_client_duoc_goi_http =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.client..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web.client..", "java.net.http..")
                    .because("worker biết đúng hai endpoint, và chỉ một package được biết "
                            + "địa chỉ của oj-api (bất biến #3)");

    @ArchTest
    static final ArchRule luat7b_chi_client_duoc_noi_amqp =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.client..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.amqp..", "com.rabbitmq..")
                    .because("một kết nối AMQP là một đường ra khỏi tiến trình y hệt một lời "
                            + "gọi HTTP. Bề mặt phụ thuộc của worker phải đọc được bằng MỘT "
                            + "package (bất biến #3, Bước 6.4)");

    @ArchTest
    static final ArchRule khong_class_nao_biet_jdbc =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..")
                    .because("worker không có DataSource và sẽ không bao giờ có. Nếu một "
                            + "nhiệm vụ có vẻ cần worker đọc DB thì dữ liệu đó phải nằm trong "
                            + "oj-contract — dừng lại và hỏi");
}
