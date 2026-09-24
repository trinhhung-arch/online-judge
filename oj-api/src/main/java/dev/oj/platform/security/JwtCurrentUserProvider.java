package dev.oj.platform.security;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hiện thực M4 của {@link CurrentUserProvider} — Bước 4.5.
 *
 * <p>Đây là toàn bộ phần thay đổi mà seam {@code CurrentUserProvider} hứa từ M1: không một
 * use-case nào đã viết phải đổi chữ ký, không một controller nào phải nhận thêm tham số
 * {@code userId}. {@code FixedDevUserProvider} và {@code DevSecurityConfig} đã bị xoá cùng
 * lần thay này — không để lại, kể cả sau {@code @ConditionalOnMissingBean}, vì một cửa hậu
 * còn nằm trong mã nguồn là một cửa hậu chờ được bật lại.
 *
 * <p>Việc nhận dạng nằm ở {@link JwtAuthFilter} và {@link CurrentUserHolder}; class này nối
 * hai thứ đó vào interface mà phần còn lại của hệ thống đã dùng suốt sáu tuần qua — và làm
 * thêm đúng một việc: hạ vai trò của ADMIN chưa bật 2FA.
 *
 * <h2>★ Vì sao cổng 2FA nằm Ở ĐÂY chứ không ở {@code RequiresRoleAdvisorConfig}</h2>
 * Bản trước hỏi cổng trong advisor, và chỉ khi use-case khai {@code @RequiresRole(ADMIN)}.
 * Nhưng quyền ADMIN còn đi qua cửa khác: {@code DownloadTestdataUseCase} khai SETTER rồi
 * gọi {@code isAdmin()} để bỏ điều kiện chủ sở hữu, nên một ADMIN chưa bật 2FA tải được
 * testdata <b>mọi đề</b> — đúng thứ mà {@code DatabaseTwoFactorGate} ghi là lý do cổng tồn
 * tại. Cùng đường vòng ấy mở cho việc đọc source mọi người, sửa đề người khác, xem đề trước
 * giờ thi và xem bảng xếp hạng lúc đóng băng.
 *
 * <p>Hạ vai trò ngay lúc trả danh tính thì mọi {@code isAdmin()}, mọi {@code role()} đưa vào
 * SQL, đều nhận vai trò đã hạ — kể cả những chỗ chưa được viết. {@code HaiLopKhongDiVongIT}
 * ghim từng chỗ hiện có.
 *
 * <h2>Hỏi một lần mỗi request</h2>
 * {@code current()} bị gọi nhiều lần trong một request (advisor, rồi use-case, rồi use-case
 * lồng bên trong). Kết quả được ghi lại vào {@link CurrentUserHolder}, nên cổng chỉ tốn một
 * lượt đọc {@code user_two_factor} — và chỉ với token ADMIN, thứ hiếm nhất hệ thống.
 */
@Component
public class JwtCurrentUserProvider implements CurrentUserProvider {

    /**
     * ★ {@code ObjectProvider} chứ KHÔNG phải {@code TwoFactorGate} — bắt buộc.
     *
     * <p>Class này được tiêm vào {@code RequiresRoleAdvisorConfig}, một bean hạ tầng mà Spring
     * dựng <b>trước mọi bean thường</b>. Nhận thẳng {@code TwoFactorGate} nghĩa là kéo cả chuỗi
     * {@code TotpChecker → JdbcTwoFactorRepository → appJdbcClient → DataSource} lên cùng thời
     * điểm ấy — tức là {@code DataSource} được tạo TRƯỚC khi các {@code BeanPostProcessor} kịp
     * đăng ký, và không còn cái nào bọc được nó.
     *
     * <p>Đo thật ngày 2026-09-05 (lúc cổng còn ở advisor): {@code DemQuery} — bộ đếm truy vấn
     * chống N+1, chính là một {@code BeanPostProcessor} — im lặng đếm ra 0 ở mọi test, và mọi
     * ngưỡng chống N+1 khác vẫn XANH vì 0 luôn nhỏ hơn mọi trần.
     */
    private final ObjectProvider<TwoFactorGate> congHaiLop;

    public JwtCurrentUserProvider(ObjectProvider<TwoFactorGate> congHaiLop) {
        this.congHaiLop = congHaiLop;
    }

    @Override
    public CurrentUser current() {
        CurrentUser nguoiGoi = CurrentUserHolder.batBuoc();
        if (!nguoiGoi.isAdmin() || CurrentUserHolder.daQuaCongHaiLop()) {
            return nguoiGoi;
        }
        CurrentUser hieuLuc = congHaiLop.getObject().duocDungQuyenAdmin(nguoiGoi.id())
                ? nguoiGoi
                : nguoiGoi.haVaiTroViChuaBatHaiLop();
        CurrentUserHolder.datSauCongHaiLop(hieuLuc);
        return hieuLuc;
    }
}
