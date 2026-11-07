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

class AuthorizationIT extends PostgresIT {

    @Autowired AnonymizeAccountUseCase anonymize;
    @Autowired GetProfileUseCase getProfile;
    @Autowired SubmitSolutionUseCase submitSolution;
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
            assertThat(AopUtils.isAopProxy(getSubmission)).isTrue();
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

    private void goHaiLopCuaAdmin() {
        jdbc.sql("DELETE FROM user_two_factor WHERE user_id = :id")
                .param("id", ADMIN_ID).update();
    }

    private void batHaiLopCho(long userId) {
        jdbc.sql("""
                INSERT INTO user_two_factor (user_id, secret_enc, enabled, confirmed_at)
                VALUES (:id, 'khong-can-giai-ma-duoc', TRUE, now())
                ON CONFLICT (user_id) DO UPDATE SET enabled = TRUE, confirmed_at = now()
                """).param("id", userId).update();
    }

    @Nested
    @DisplayName("★ V11 · ADMIN chưa bật 2FA thì không dùng được quyền ADMIN")
    class CongHaiLop {

        @Test
        @DisplayName("★ vai trò ĐỦ nhưng chưa bật 2FA → 403 auth.can_hai_lop, không phải thiếu quyền")
        void admin_chua_bat_2fa_bi_chan() {
            goHaiLopCuaAdmin();
            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                assertThatThrownBy(() -> anonymize.thucHien(USER_ID))
                        .isInstanceOf(DomainException.class)
                        .hasFieldOrPropertyWithValue("kind", DomainException.Kind.FORBIDDEN)
                        .hasFieldOrPropertyWithValue("code", "auth.can_hai_lop");
                assertThat(phien).isNotNull();
            }

            assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", USER_ID).query(String.class).single())
                    .as("bị chặn nghĩa là KHÔNG chạm dữ liệu, không phải im lặng không làm gì")
                    .isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("★ đường BẬT 2FA phải nằm dưới cổng — nếu không thì ADMIN tự khoá mình vĩnh viễn")
        void duong_bat_2fa_khong_bi_chan() {
            goHaiLopCuaAdmin();
            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                assertThat(getProfile.thucHien().handle()).isEqualTo("admin");
                assertThat(phien).isNotNull();
            }
        }

        @Test
        @DisplayName("bật 2FA rồi thì quyền ADMIN dùng được")
        void bat_roi_thi_qua() {
            goHaiLopCuaAdmin();
            batHaiLopCho(ADMIN_ID);
            try (var phien = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
                anonymize.thucHien(USER_ID);
                assertThat(phien).isNotNull();
            }
            assertThat(jdbc.sql("SELECT status FROM users WHERE id = :id")
                    .param("id", USER_ID).query(String.class).single())
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
