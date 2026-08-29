package dev.oj.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import dev.oj.platform.security.InternalAccess;
import dev.oj.platform.security.PublicAccess;
import dev.oj.platform.security.RequiresRole;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

/**
 * LUẬT 5–8 — cách viết code <b>bên trong</b> một module. Nửa kia của {@link ArchitectureTest},
 * nơi giữ LUẬT 1–4 về ranh giới giữa các module.
 *
 * <p>Tách ra ở M4 vì file gốc vượt trần 300 dòng ({@code CLAUDE.md} mục 7). Đường cắt không
 * tuỳ tiện: bốn luật đầu trả lời <i>"module nào được biết module nào"</i> và đọc cùng sơ đồ ở
 * {@code CLAUDE.md} mục 3; bốn luật sau trả lời <i>"trong một file được viết gì"</i> và mỗi
 * luật gắn với một bất biến cụ thể ở mục 2 — #5 SQL, #12 log, #10 LLM, #11 phân quyền.
 *
 * <p><b>Vi phạm bất kỳ luật nào = fail CI.</b>
 *
 * <p>Cần {@code src/test/resources/archunit.properties} với
 * {@code archRule.failOnEmptyShould=false}, giống {@link ArchitectureTest}.
 */
@AnalyzeClasses(
        packages = "dev.oj",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTest {

    /**
     * Client LLM. Chỉ {@code dev.oj.ai} được chạm (LUẬT 7 — bất biến #10).
     * Thêm nhà cung cấp = thêm một dòng ở đây.
     */
    private static final String[] LLM_CLIENT = {
            "dev.langchain4j..",
            "com.openai..",
            "com.anthropic..",
            "org.springframework.ai..",
            "io.github.sashirestela.."
    };

    // =========================================================================
    // LUẬT 5 — không nối chuỗi vào SQL (bất biến #5, SEC2)
    //
    // ⚠️ Giới hạn thật của công cụ: từ Java 9, `a + b` biên dịch thành invokedynamic
    // (StringConcatFactory), ArchUnit KHÔNG thấy được. Nên luật 5 được ép bằng ba lớp
    // gián tiếp dưới đây, cộng một lớp thứ tư ngoài ArchUnit — xem 5d.
    // =========================================================================

    /**
     * 5a — SQL chỉ sống trong {@code infrastructure}. Không có SQL ở controller hay use-case.
     *
     * <p><b>Một ngoại lệ, và chỉ một:</b> {@code dev.oj.platform.config} được chạm
     * {@code org.springframework.jdbc} và {@code javax.sql} vì nó là chỗ tạo bean
     * {@code DataSource}, {@code JdbcClient} và {@code JdbcTransactionManager} — hai pool tách
     * nhau theo {@code postgres-design.md} mục 11. Nó dựng <i>công cụ</i>, không viết câu lệnh.
     *
     * <p>Luật vẫn còn răng: một câu SQL đặt trong {@code platform.config} sẽ không bị luật này
     * bắt, nhưng nó cũng không có lý do gì để tồn tại ở đó, và review sẽ thấy ngay. Cái luật
     * này thật sự chặn là SQL trong controller và trong use-case.
     */
    @ArchTest
    static final ArchRule luat5a_sql_chi_o_infrastructure =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "dev.oj..infrastructure..", "dev.oj.platform.config..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..", "java.sql..", "javax.sql..")
                    .because("thu hẹp bề mặt cần rà soát: mọi câu SQL của hệ thống nằm trong "
                            + "đúng một loại package");

    /** 5b — trong {@code infrastructure}, cấm mọi công cụ dựng chuỗi động. */
    @ArchTest
    static final ArchRule luat5b_khong_dung_chuoi_dong_trong_infrastructure =
            noClasses()
                    .that().resideInAPackage("dev.oj..infrastructure..")
                    .should().callMethod(String.class, "format", String.class, Object[].class)
                    // String.formatted(Object...) là String.format viết ngược — cùng một việc,
                    // tên khác. Thiếu dòng này thì text block + .formatted() lách qua luật.
                    .orShould().callMethod(String.class, "formatted", Object[].class)
                    .orShould().callMethod(String.class, "concat", String.class)
                    .orShould().callMethod(String.class, "join", CharSequence.class, Iterable.class)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.StringBuilder")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.StringBuffer")
                    .because("mọi câu SQL là hằng số có named parameter. Cần WHERE động thì viết "
                            + "`(:x IS NULL OR cot = :x)` như queries/duong_nong.sql số 6, "
                            + "không phải nối chuỗi (bất biến #5)");

    /** 5c — chỉ {@code JdbcClient}. {@code Statement} trần và {@code JdbcTemplate} đều bị cấm. */
    @ArchTest
    static final ArchRule luat5c_chi_jdbcclient =
            noClasses()
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.sql.Statement")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                    .because("java.sql.Statement không có tham số, JdbcTemplate nhận `Object...` theo "
                            + "vị trí. Cả hai đều làm việc nối chuỗi trở nên tiện. JdbcClient thì không");

    // -------------------------------------------------------------------------
    // 5d — lớp cuối, KHÔNG làm được bằng ArchUnit. Thêm vào .github/workflows/ci.yml:
    //
    //   - name: Cam noi chuoi SQL
    //     run: |
    //       ! grep -rInE '"\s*\+|\+\s*"' --include='*.java' \
    //            oj-api/src/main/java/dev/oj/*/infrastructure/
    //
    // Thô, nhưng nó bắt được đúng thứ invokedynamic giấu mất.
    // -------------------------------------------------------------------------

    // =========================================================================
    // LUẬT 6 — không System.out / System.err (bất biến #12)
    // =========================================================================

    @ArchTest
    static final ArchRule luat6_khong_system_out =
            NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                    .because("không truy được sự cố xuyên API → queue → worker nếu thiếu traceId. "
                            + "Dùng logger có traceId (bất biến #12). "
                            + "Luật này bao gồm cả Throwable.printStackTrace()");

    // =========================================================================
    // LUẬT 7 — chỉ dev.oj.ai được import client LLM (bất biến #10, AI1)
    // =========================================================================

    @ArchTest
    static final ArchRule luat7_llm_chi_o_package_ai =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.ai..")
                    .should().dependOnClassesThat().resideInAnyPackage(LLM_CLIENT)
                    .because("một lần LLM chậm nằm trên đường verdict là cả hệ thống chấm bài đứng. "
                            + "AI1 = 0ms thêm vào đường chấm, và cách rẻ nhất để giữ nó là "
                            + "không cho package nào khác gọi được LLM (bất biến #10)");

    // =========================================================================
    // LUẬT 8 — mọi use-case phải TUYÊN BỐ lập trường phân quyền (bất biến #11)
    // Bật ở M4 cùng Bước 4.6.
    // =========================================================================

    /**
     * ★ Bản phác trong tài liệu chỉ đòi {@code @RequiresRole} trên use-case
     * {@code @Transactional}. Luật thật ở đây <b>mạnh hơn hẳn</b>, vì hai lý do đo được:
     *
     * <ol>
     *   <li><b>Vị từ {@code @Transactional} bắt hụt.</b> {@code SubmitSolutionUseCase} —
     *       use-case ghi quan trọng nhất hệ thống — cố ý <i>không</i> dùng annotation đó mà
     *       dựng {@link org.springframework.transaction.support.TransactionTemplate} bằng tay,
     *       vì nó có một dòng phải nằm ngoài transaction. Một luật dựa vào
     *       {@code @Transactional} sẽ bỏ qua đúng file nguy hiểm nhất và vẫn xanh.</li>
     *   <li><b>Use-case ĐỌC cũng rò rỉ dữ liệu.</b> Bất biến #1 nói về việc lộ testcase ẩn,
     *       ma trận hiển thị nói về source người khác trong contest, {@code audit_log} chỉ
     *       ADMIN được đọc. Giới hạn luật ở "use-case sửa dữ liệu" là để ngỏ toàn bộ mặt đọc —
     *       mặt mà một Online Judge thật sự sợ.</li>
     * </ol>
     *
     * <p>Nên luật là: <b>mọi</b> {@code *UseCase} phải mang một trong ba tuyên bố. Không có
     * mặc định im lặng, vì "quên nghĩ về phân quyền" và "đã quyết định là công khai" trông
     * giống hệt nhau trong mã nguồn, mà hậu quả thì ngược nhau.
     *
     * <p>Phần thưởng kèm theo: {@code grep -rn "@PublicAccess"} liệt kê đủ mọi lối vào không
     * cần đăng nhập của cả hệ thống. Đó là trang đầu tiên phải đọc trong buổi tấn công chéo
     * tuần 9.
     */
    @ArchTest
    static final ArchRule luat8_use_case_phai_tuyen_bo_phan_quyen =
            classes()
                    .that().resideInAPackage("dev.oj..application.usecase..")
                    .and().haveSimpleNameEndingWith("UseCase")
                    .should().beAnnotatedWith(RequiresRole.class)
                    .orShould().beAnnotatedWith(PublicAccess.class)
                    .orShould().beAnnotatedWith(InternalAccess.class)
                    .because("kiểm quyền ở controller là kiểm ở chỗ dễ đi vòng nhất — cùng một "
                            + "use-case còn được gọi từ consumer, job nền và test (bất biến #11). "
                            + "Ba annotation là ba lập trường, và không có lập trường thứ tư là "
                            + "'chưa nghĩ tới'");

    // =========================================================================
    // Luật bật sau — đừng viết bây giờ, nhưng biết chúng sẽ tới
    // =========================================================================
    //
    //  Bất biến #3 (worker không có DataSource) KHÔNG kiểm được ở đây — oj-worker không nằm
    //  trên classpath của oj-api. Nó là một test riêng trong oj-worker, Bước M1-10:
    //  đọc oj-worker/pom.xml và fail nếu thấy spring-boot-starter-data-jdbc, postgresql,
    //  flyway-core, lettuce-core hay minio.
}
