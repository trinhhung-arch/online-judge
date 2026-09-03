package dev.oj.worker.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Luật ArchUnit 6 và 7 của {@code cau-truc-source.md} mục 6. Chúng <b>phải</b> chạy ở đây:
 * {@code oj-worker} không nằm trên classpath của {@code oj-api}, nên
 * {@code dev.oj.architecture.ArchitectureTest} không nhìn thấy một class nào của worker.
 *
 * <p>Cho tới khi {@code oj-worker} trở thành một Maven module thật, hai luật này không có chỗ
 * nào để chạy — và bất biến #4 ("mọi mã người dùng chạy trong isolate") được ép bằng lời hứa.
 */
@AnalyzeClasses(
        packages = "dev.oj.worker",
        importOptions = ImportOption.DoNotIncludeTests.class)
class WorkerArchitectureTest {

    /**
     * ★ LUẬT 6 — bất biến #4, SEC1. <b>Chỉ {@code worker.sandbox} được spawn tiến trình.</b>
     *
     * <p>Đây là luật quan trọng nhất của cả dự án được diễn đạt thành một câu máy kiểm được.
     * Compiler bomb và fork bomb là có thật; {@code ProcessBuilder} + timeout <b>không phải</b>
     * sandbox. Nếu bạn thấy mình đang viết {@code new ProcessBuilder("g++", ...)} thì đã sai —
     * kể cả "chỉ để thử nhanh", vì bản tạm đó không bao giờ bị xoá, nó chỉ bị quên
     * ({@code build-order.md} Phần 0 điểm G).
     *
     * <p>Ở M1 luật này xanh một cách tuyệt đối: package {@code sandbox} còn rỗng, và
     * {@code ScriptedJudgeRunner} không thực thi gì cả.
     */
    @ArchTest
    static final ArchRule luat6_chi_sandbox_duoc_spawn_tien_trinh =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.sandbox..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.Process")
                    .because("mọi thực thi mã người dùng đi qua isolate, kể cả bước biên dịch "
                            + "(bất biến #4). ProcessBuilder + timeout không phải sandbox");

    /**
     * ★ LUẬT 7 — bất biến #3, S1/S2. <b>Chỉ {@code worker.client} biết địa chỉ của API.</b>
     *
     * <p>Bề mặt phụ thuộc của worker phải đọc được bằng một file. Một lời gọi HTTP mọc ở
     * {@code pipeline} hay {@code run} là bước đầu tiên của việc worker tự đi lấy dữ liệu nó
     * "cần" — và bước thứ hai luôn là một {@code DataSource}.
     */
    @ArchTest
    static final ArchRule luat7_chi_client_duoc_goi_http =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.client..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.web.client..", "java.net.http..")
                    .because("worker biết đúng hai endpoint, và chỉ một package được biết "
                            + "địa chỉ của oj-api (bất biến #3)");

    /**
     * ★ Luật 7 mở rộng ở M6 — <b>AMQP cũng là một đường ra khỏi tiến trình</b>.
     *
     * <p>Bước 6.4 thêm một kết nối RabbitMQ vào worker. Luật 7 nguyên bản chỉ kể tên hai thư
     * viện HTTP, nên nó sẽ im lặng cho phép một {@code @RabbitListener} mọc ở
     * {@code worker.pipeline} hay {@code worker.run}. Điều luật 7 bảo vệ không phải là "HTTP"
     * mà là: <i>bề mặt phụ thuộc của worker phải đọc được bằng một package</i>.
     *
     * <p>Bất biến #3 nói worker chỉ biết bốn đường dẫn trong {@code JudgeEndpoints}. Một tiếng
     * chuông AMQP không phá điều đó — nó không mang dữ liệu nào, và worker vẫn phải gọi
     * {@code claim} để lấy việc. Nhưng bước thứ hai của mọi vi phạm bất biến #3 luôn bắt đầu
     * bằng "chỗ này cũng có sẵn một kết nối rồi".
     */
    @ArchTest
    static final ArchRule luat7b_chi_client_duoc_noi_amqp =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.worker.client..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.amqp..", "com.rabbitmq..")
                    .because("một kết nối AMQP là một đường ra khỏi tiến trình y hệt một lời "
                            + "gọi HTTP. Bề mặt phụ thuộc của worker phải đọc được bằng MỘT "
                            + "package (bất biến #3, Bước 6.4)");

    /**
     * Mặt còn lại của bất biến #3, ở tầng kiểu dữ liệu: worker không được biết JDBC tồn tại.
     * {@code WorkerHasNoDataSourceTest} chặn ở tầng {@code pom.xml}; luật này chặn ở tầng
     * import, phòng trường hợp một dependency khác vô tình kéo driver vào classpath.
     */
    @ArchTest
    static final ArchRule khong_class_nao_biet_jdbc =
            noClasses()
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "java.sql..", "javax.sql..", "org.springframework.jdbc..")
                    .because("worker không có DataSource và sẽ không bao giờ có. Nếu một "
                            + "nhiệm vụ có vẻ cần worker đọc DB thì dữ liệu đó phải nằm trong "
                            + "oj-contract — dừng lại và hỏi");
}
