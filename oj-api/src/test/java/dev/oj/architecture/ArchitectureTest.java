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

/**
 * Ranh giới kiến trúc của Online Judge — ép bằng CI, không bằng lời hứa.
 *
 * <p>Nguồn: {@code CLAUDE.md} mục 3 (bốn luật cứng) và {@code nfrplan.md} 8.2 (ba luật NFR).
 * Vị trí trong lịch: {@code docs/build-order.md} Bước 0.7 — viết ở M0, khi chưa có module nào.
 *
 * <p><b>Vì sao viết file này trước khi có code:</b> lúc này nó rẻ và luôn xanh. Viết ở tuần 5
 * thì nó đỏ 200 chỗ, và một test đỏ 200 chỗ luôn kết thúc bằng việc bị {@code @Disabled}.
 *
 * <p><b>Bắt buộc kèm theo:</b> {@code src/test/resources/archunit.properties} với
 * {@code archRule.failOnEmptyShould=false}. Không có file đó, mọi luật dưới đây FAIL ở M0
 * vì chưa có class nào khớp — đúng cái làm hỏng ý định của Bước 0.7.
 *
 * <p><b>Vi phạm bất kỳ luật nào = fail CI.</b> Nếu một nhiệm vụ buộc bạn vi phạm:
 * dừng lại và nói ra ({@code CLAUDE.md} mục 5), đừng nới luật ở đây.
 *
 * <h2>File này giữ LUẬT 1–4 — ranh giới giữa các module</h2>
 * LUẬT 5–8 nói về <i>cách viết code bên trong một module</i> (SQL, log, LLM, phân quyền) và
 * sống ở {@link CodingRulesTest}. Tách ở M4 vì file gốc vượt trần 300 dòng của
 * {@code CLAUDE.md} mục 7 — cùng một luật áp cho chính bộ test, không có ngoại lệ.
 */
@AnalyzeClasses(
        packages = "dev.oj",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // =========================================================================
    // Hằng số — sửa ở đây, không rải trong từng luật
    // =========================================================================

    /** Các module nghiệp vụ trong {@code oj-api}. Thêm module = thêm một dòng ở đây và ở LUẬT 3. */
    private static final List<String> MODULES =
            List.of("platform", "identity", "problems", "judging", "contests", "ai");

    /** Framework mà {@code domain} không được biết. Domain là Java thuần. */
    private static final String[] FRAMEWORK = {
            "org.springframework..",
            "jakarta.persistence..", "jakarta.transaction..", "jakarta.servlet..",
            "com.fasterxml.jackson..",
            "org.hibernate..",
            "java.sql..", "javax.sql..",          // domain không biết JDBC tồn tại
            "com.rabbitmq..", "com.zaxxer.hikari..",
            "io.lettuce..", "org.redisson..",
            "io.minio.."
    };

    /**
     * {@code application} có được thấy {@code infrastructure} không?
     *
     * <p>Sơ đồ trong {@code CLAUDE.md} mục 3 vẽ {@code application ──▶ infrastructure}, nhưng
     * chính {@code CLAUDE.md} và {@code build-order.md} lại thiết kế theo port/adapter:
     * {@code application} định nghĩa interface ({@code SubmissionRepository}...),
     * {@code infrastructure} hiện thực chúng. Hai điều đó không sống chung được — nếu
     * {@code application} import {@code infrastructure} thì use-case phụ thuộc {@code JdbcClient}
     * và test bằng fake repository trở nên vô nghĩa.
     *
     * <p>Mặc định ở đây là <b>chiều đảo (false)</b>: {@code infrastructure ──▶ application}.
     * Wiring do lớp {@code config} trong {@code api} làm. Đổi thành {@code true} nếu hai bạn
     * quyết định khác — nhưng hãy ghi ADR, đừng đổi lặng lẽ.
     */
    private static final boolean APPLICATION_DUOC_THAY_INFRASTRUCTURE = false;

    /**
     * Class nằm ngoài JDK và ngoài chính {@code oj-contract} (dùng cho LUẬT 4).
     *
     * <p>Viết tay thay vì {@code resideOutsideOfPackages("java..")} vì kiểu nguyên thuỷ
     * ({@code int}, {@code void}) và mảng có package rỗng — cách viết tắt sẽ báo nhầm.
     *
     * <p><b>Mảng phải quy về kiểu phần tử qua {@code getBaseComponentType()}.</b>
     * {@code JavaClass.getName()} của một mảng trả về mô tả nhị phân của JVM
     * ({@code [Ldev.oj.contract.Verdict;}), không phải {@code dev.oj.contract.Verdict[]} —
     * nên cắt chuỗi {@code "[]"} không khớp gì cả, và mọi {@code enum} trong contract bị báo
     * nhầm: {@code values()} và field tổng hợp {@code ENUM$VALUES} đều có kiểu mảng. Luật 4
     * khi đó đỏ vì chính những class nó bảo vệ, và một luật đỏ vô cớ là một luật sắp bị tắt.
     *
     * <p>Phải khai báo TRƯỚC luật 4: field static khởi tạo theo thứ tự văn bản, đặt sau
     * thì luật 4 nhận {@code null}.
     */
    private static final DescribedPredicate<JavaClass> NGOAI_JDK =
            new DescribedPredicate<>("nằm ngoài JDK và ngoài chính oj-contract") {
                @Override
                public boolean test(JavaClass clazz) {
                    String ten = clazz.getBaseComponentType().getName();
                    if (ten.indexOf('.') < 0) {
                        return false;   // int, long, void, boolean...
                    }
                    return !(ten.startsWith("java.") || ten.startsWith("dev.oj.contract."));
                }
            };

    // =========================================================================
    // LUẬT 1 — domain là Java thuần
    // CLAUDE.md mục 3 luật 1
    // =========================================================================

    @ArchTest
    static final ArchRule luat1_domain_khong_biet_framework =
            noClasses()
                    .that().resideInAPackage("dev.oj..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK)
                    .because("domain là Java thuần: bất biến nghiệp vụ phải test được bằng JUnit trần, "
                            + "không Spring context, chạy dưới 1 giây (CLAUDE.md mục 3 luật 1)");

    // =========================================================================
    // LUẬT 2 — module X không import infrastructure của module Y
    // CLAUDE.md mục 3 luật 2. Chỉ được import package public của Y.
    // =========================================================================

    @ArchTest
    static final ArchRule luat2_infrastructure_la_rieng_tu =
            classes()
                    .that().resideInAPackage("dev.oj..")
                    .should(khongChamInfrastructureCuaModuleKhac())
                    .because("infrastructure là cách một module tự cài đặt mình. Module khác chạm vào "
                            + "nghĩa là đổi một câu SQL ở judging làm vỡ contests (CLAUDE.md mục 3 luật 2)");

    /** Bổ sung — chiều trong một module: {@code api ──▶ application ──▶ domain}. */
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

    // =========================================================================
    // LUẬT 3 — chiều module một chiều, không có chiều ngược
    //   identity → problems → judging → contests   ·   ai → judging
    //   platform: ai cũng import được
    // CLAUDE.md mục 3 luật 3
    // =========================================================================

    @ArchTest
    static final ArchRule luat3_chieu_module_mot_chieu =
            layeredArchitecture().consideringOnlyDependenciesInAnyPackage("dev.oj..")
                    .withOptionalLayers(true)   // BẮT BUỘC: ở M0 mọi tầng đều rỗng

                    .layer("app").definedBy("dev.oj")            // chỉ class ngay tại dev.oj (composition root)
                    .layer("platform").definedBy("dev.oj.platform..")
                    .layer("identity").definedBy("dev.oj.identity..")
                    .layer("problems").definedBy("dev.oj.problems..")
                    .layer("judging").definedBy("dev.oj.judging..")
                    .layer("contests").definedBy("dev.oj.contests..")
                    .layer("ai").definedBy("dev.oj.ai..")

                    // Đọc từ dưới lên: ai được phép gọi tầng này.
                    .whereLayer("contests").mayOnlyBeAccessedByLayers("app")
                    .whereLayer("ai").mayOnlyBeAccessedByLayers("app")
                    .whereLayer("judging").mayOnlyBeAccessedByLayers("contests", "ai", "app")
                    .whereLayer("problems").mayOnlyBeAccessedByLayers("judging", "contests", "ai", "app")
                    .whereLayer("identity").mayOnlyBeAccessedByLayers("problems", "judging", "contests", "ai", "app")
                    // "platform" cố ý KHÔNG bị giới hạn — mọi module import được (CLAUDE.md mục 3).

                    .because("problems không biết judging tồn tại. Một chiều ngược lọt vào là "
                            + "hai module dính liền vĩnh viễn (CLAUDE.md mục 3 luật 3)");

    /** Mặt còn lại của LUẬT 3: {@code platform} là nền, không được biết nghiệp vụ. */
    @ArchTest
    static final ArchRule luat3b_platform_khong_biet_nghiep_vu =
            noClasses()
                    .that().resideInAPackage("dev.oj.platform..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "dev.oj.identity..", "dev.oj.problems..",
                            "dev.oj.judging..", "dev.oj.contests..", "dev.oj.ai..")
                    .because("platform chứa config, error, security, trace — nếu nó biết judging thì "
                            + "nó không còn là nền, nó là một module nghiệp vụ thứ bảy");

    // -------------------------------------------------------------------------
    // ⚠️ Ghi chú cho tuần 14–15, đọc trước khi viết module `ai`:
    //
    //   FR-AI-02 bắt AI review phải tắt trong thời gian contest, kiểm theo `contest.status`.
    //   Nhưng LUẬT 3 cấm `ai → contests`. Đừng nới luật để lách.
    //
    //   Cách đi đúng: đặt interface `ContestWindowQuery` trong `dev.oj.platform`,
    //   `contests.infrastructure` hiện thực nó, `ai` (và `problems` cho FR-PROB-11)
    //   chỉ phụ thuộc `platform`. Đồ thị module vẫn không có chu trình.
    // -------------------------------------------------------------------------

    // =========================================================================
    // LUẬT 4 — oj-contract không import gì ngoài JDK
    // CLAUDE.md mục 3 luật 4. Nó là biên giới giữa hai người và hai tiến trình.
    // =========================================================================

    @ArchTest
    static final ArchRule luat4_contract_chi_biet_jdk =
            noClasses()
                    .that().resideInAPackage("dev.oj.contract..")
                    .should().dependOnClassesThat(NGOAI_JDK)
                    .because("oj-contract được đóng băng ở tuần 1 và dùng chung bởi oj-api lẫn oj-worker. "
                            + "Một annotation Jackson lọt vào đây là bắt đầu của việc worker phải biết "
                            + "hạ tầng của API (CLAUDE.md mục 3 luật 4)");

    // =========================================================================
    // Trợ giúp
    // =========================================================================

    /** Tên module của một class, hoặc {@code null} nếu class không thuộc module nào. */
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
                    return;   // composition root, oj-contract, class sinh tự động
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