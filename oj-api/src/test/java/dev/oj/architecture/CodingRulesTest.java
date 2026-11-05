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

@AnalyzeClasses(
        packages = "dev.oj",
        importOptions = ImportOption.DoNotIncludeTests.class)
class CodingRulesTest {

    private static final String[] LLM_CLIENT = {
            "dev.langchain4j..",
            "com.openai..",
            "com.anthropic..",
            "org.springframework.ai..",
            "io.github.sashirestela.."
    };

    @ArchTest
    static final ArchRule luat5a_sql_chi_o_infrastructure =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "dev.oj..infrastructure..", "dev.oj.platform.config..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework.jdbc..", "java.sql..", "javax.sql..")
                    .because("thu hẹp bề mặt cần rà soát: mọi câu SQL của hệ thống nằm trong "
                            + "đúng một loại package");

    @ArchTest
    static final ArchRule luat5b_khong_dung_chuoi_dong_trong_infrastructure =
            noClasses()
                    .that().resideInAPackage("dev.oj..infrastructure..")
                    .should().callMethod(String.class, "format", String.class, Object[].class)
                    .orShould().callMethod(String.class, "formatted", Object[].class)
                    .orShould().callMethod(String.class, "concat", String.class)
                    .orShould().callMethod(String.class, "join", CharSequence.class, Iterable.class)
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.StringBuilder")
                    .orShould().dependOnClassesThat().haveFullyQualifiedName("java.lang.StringBuffer")
                    .because("mọi câu SQL là hằng số có named parameter. Cần WHERE động thì viết "
                            + "`(:x IS NULL OR cot = :x)` như queries/duong_nong.sql số 6, "
                            + "không phải nối chuỗi (bất biến #5)");

    @ArchTest
    static final ArchRule luat5c_chi_jdbcclient =
            noClasses()
                    .should().dependOnClassesThat().haveFullyQualifiedName("java.sql.Statement")
                    .orShould().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.jdbc.core.JdbcTemplate")
                    .because("java.sql.Statement không có tham số, JdbcTemplate nhận `Object...` theo "
                            + "vị trí. Cả hai đều làm việc nối chuỗi trở nên tiện. JdbcClient thì không");

    @ArchTest
    static final ArchRule luat6_khong_system_out =
            NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS
                    .because("không truy được sự cố xuyên API → queue → worker nếu thiếu traceId. "
                            + "Dùng logger có traceId (bất biến #12). "
                            + "Luật này bao gồm cả Throwable.printStackTrace()");

    @ArchTest
    static final ArchRule luat7_llm_chi_o_package_ai =
            noClasses()
                    .that().resideOutsideOfPackage("dev.oj.ai..")
                    .should().dependOnClassesThat().resideInAnyPackage(LLM_CLIENT)
                    .because("một lần LLM chậm nằm trên đường verdict là cả hệ thống chấm bài đứng. "
                            + "AI1 = 0ms thêm vào đường chấm, và cách rẻ nhất để giữ nó là "
                            + "không cho package nào khác gọi được LLM (bất biến #10)");

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

    @ArchTest
    static final ArchRule luat9_doc_de_khong_cham_kho_doi_tuong =
            noClasses()
                    .that().haveSimpleName("GetProblemUseCase")
                    .or().haveSimpleName("ListProblemsUseCase")
                    .or().haveSimpleName("StatementService")
                    .should().dependOnClassesThat()
                    .haveSimpleName("TestdataStore")
                    .because("nfrplan.md 7.2: MinIO chết thì đề đã cache vẫn phải xem được. "
                            + "Đường đọc đề đi qua Postgres và rendered_statements, không qua "
                            + "kho đối tượng — và đó phải là một luật, không phải một sự trùng hợp");
}
