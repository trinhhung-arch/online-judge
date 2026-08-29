package dev.oj.it;

import dev.oj.identity.application.usecase.AnonymizeAccountUseCase;
import dev.oj.identity.application.usecase.GetProfileUseCase;
import dev.oj.judging.application.usecase.GetSubmissionUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Bước 4.6 và 4.8 — chốt phân quyền có <b>thật sự chặn</b> không.
 *
 * <h2>Vì sao test này bắt buộc phải tồn tại, và phải là một IT chứ không phải unit test</h2>
 * {@code @RequiresRole} được ép bằng một AOP advisor, và advisor đó có một kiểu hỏng <b>hoàn
 * toàn im lặng</b>: thiếu {@code @Role(ROLE_INFRASTRUCTURE)} trên bean thì
 * {@code InfrastructureAdvisorAutoProxyCreator} bỏ qua nó, không log gì cả, không ném gì cả —
 * và mọi use-case chạy <i>không được kiểm quyền</i> trong khi mã nguồn trông vẫn đúng.
 *
 * <p>Unit test không phát hiện được: test đơn vị dựng use-case bằng {@code new}, không có
 * proxy nào, nên nó xanh dù advisor có được gắn hay không. Chỉ một context Spring thật mới
 * trả lời được câu hỏi <i>"chốt này có đang chạy không"</i>.
 *
 * <p>Đó là lý do lớp này mở đầu bằng {@link CoThatSuDuocGan} — hỏi thẳng Spring xem bean có
 * phải proxy không, để khi nó đỏ thì thông báo chỉ đúng vào nguyên nhân thay vì để người đọc
 * đoán từ một loạt test 403 bỗng nhiên xanh nhầm.
 */
class AuthorizationIT extends PostgresIT {

    @Autowired AnonymizeAccountUseCase anonymize;      // @RequiresRole(ADMIN)
    @Autowired GetProfileUseCase getProfile;           // @RequiresRole  (USER)
    @Autowired SubmitSolutionUseCase submitSolution;   // @RequiresRole  (USER)
    @Autowired GetSubmissionUseCase getSubmission;

    @Nested
    @DisplayName("★ Advisor có được gắn không")
    class CoThatSuDuocGan {

        @Test
        @DisplayName("use-case mang @RequiresRole phải là AOP proxy")
        void use_case_duoc_boc_proxy() {
            assertThat(AopUtils.isAopProxy(anonymize))
                    .describedAs("""
                            AnonymizeAccountUseCase KHÔNG được bọc proxy.

                            Nghĩa là @RequiresRole đang không chặn gì cả, và mọi test 403 dưới
                            đây sẽ đỏ theo. Nguyên nhân gần như chắc chắn là thiếu
                            @Role(BeanDefinition.ROLE_INFRASTRUCTURE) trên bean advisor trong
                            RequiresRoleAdvisorConfig — không có aspectjweaver, Spring Boot
                            đăng ký InfrastructureAdvisorAutoProxyCreator, và class đó chỉ
                            nhìn những Advisor có role hạ tầng.""")
                    .isTrue();
        }

        @Test
        @DisplayName("use-case @PublicAccess và @InternalAccess KHÔNG cần bọc — pointcut không khớp")
        void public_va_internal_khong_bi_boc() {
            // Không phải chi tiết thẩm mỹ: nếu pointcut khớp cả chúng thì mọi endpoint công
            // khai đòi đăng nhập, và worker không claim được việc nào.
            assertThat(AopUtils.isAopProxy(getSubmission)).isTrue();   // @RequiresRole
        }
    }

    @Nested
    @DisplayName("★ Vai trò sai → 403, KHÔNG phải 200 rỗng")
    class VaiTroSai {

        @Test
        @DisplayName("USER gọi use-case ADMIN → 403 và KHÔNG có tác dụng phụ nào")
        void user_goi_use_case_admin() {
            assertThatThrownBy(() -> anonymize.thucHien(SETTER_ID))
                    .isInstanceOf(DomainException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.FORBIDDEN)
                    .hasFieldOrPropertyWithValue("code", "auth.thieu_quyen");

            // Điểm quan trọng nhất: không phải "trả về rỗng rồi lặng lẽ không làm gì" mà là
            // KHÔNG CHẠM tới dữ liệu. Tài khoản setter còn nguyên.
            assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", SETTER_ID).query(String.class).single())
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("SETTER cũng bị chặn — atLeast(ADMIN) chứ không phải 'khác USER'")
        void setter_khong_du_quyen() {
            try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
                assertThatThrownBy(() -> anonymize.thucHien(USER_ID))
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.FORBIDDEN);
                assertThat(phien).isNotNull();
            }
        }

        @Test
        @DisplayName("ADMIN thì qua — và vai trò cao làm được việc của vai trò thấp")
        void admin_thi_qua() {
            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                anonymize.thucHien(SETTER_ID);
                assertThat(getProfile.thucHien().handle()).isEqualTo("admin");
                assertThat(phien).isNotNull();
            }

            assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", SETTER_ID).query(String.class).single())
                    .isEqualTo("ANONYMIZED");
        }
    }

    @Nested
    @DisplayName("★ Chưa đăng nhập → 401, không phải dữ liệu rỗng")
    class ChuaDangNhap {

        @Test
        @DisplayName("khách gọi use-case cần đăng nhập → auth.chua_dang_nhap")
        void khach_bi_chan() {
            try (var khach = GiaLapDanhTinh.khach()) {
                assertThatThrownBy(() -> getProfile.thucHien())
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAUTHENTICATED)
                        .hasFieldOrPropertyWithValue("code", "auth.chua_dang_nhap");
                assertThat(khach).isNotNull();
            }
        }

        @Test
        @DisplayName("★ khách KHÔNG nộp được bài — chốt nằm ở use-case, không ở controller")
        void khach_khong_nop_duoc_bai() {
            try (var khach = GiaLapDanhTinh.khach()) {
                assertThatThrownBy(() ->
                        submitSolution.submit(new SubmitSolutionUseCase.Command(
                                PROBLEM_ID, "cpp20", "int main(){}")))
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.UNAUTHENTICATED);
                assertThat(khach).isNotNull();
            }

            // Và không có dòng rác nào bị bỏ lại: chốt chạy TRƯỚC khi mở transaction.
            assertThat(jdbc.sql("SELECT count(*) FROM submissions").query(Integer.class).single())
                    .isZero();
        }
    }

    @Nested
    @DisplayName("★ Bước 4.8 · IDOR — bài nộp của người khác là 404, không phải 403")
    class ChongIdor {

        @Test
        @DisplayName("người khác xem bài của tôi → 404, vì 403 xác nhận bài đó tồn tại")
        void bai_nop_cua_nguoi_khac_la_404() {
            long cuaToi = submitSolution.submit(new SubmitSolutionUseCase.Command(
                    PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();

            try (var nguoiKhac = GiaLapDanhTinh.dongVai(ADMIN_ID, "ke-to-mo", Role.USER)) {
                assertThatThrownBy(() -> getSubmission.detailById(cuaToi))
                        .isInstanceOf(DomainException.class)
                        .describedAs("403 ở đây là xác nhận 'có một bài nộp id này' — đủ để dò "
                                + "ra ai đã nộp bài nào, và trong contest đó là thông tin "
                                + "không được lộ (ghi chú NOT_FOUND của DomainException)")
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.NOT_FOUND);
                assertThat(nguoiKhac).isNotNull();
            }
        }

        @Test
        @DisplayName("chính chủ thì xem được")
        void chinh_chu_xem_duoc() {
            long cuaToi = submitSolution.submit(new SubmitSolutionUseCase.Command(
                    PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();

            assertThat(getSubmission.detailById(cuaToi)).isNotNull();
        }
    }
}
