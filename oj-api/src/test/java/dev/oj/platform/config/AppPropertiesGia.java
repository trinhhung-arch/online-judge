package dev.oj.platform.config;

import java.time.Duration;

/**
 * {@link AppProperties} dựng sẵn cho test — <b>một chỗ duy nhất</b>.
 *
 * <h2>Vì sao file này tồn tại</h2>
 * {@code AppProperties} là một record bảy nhóm, và mọi compact constructor của nó đều ném lỗi
 * nếu con số không khớp hằng trong {@code oj-contract} hoặc không khớp một dòng trong bảng
 * giới hạn của {@code oj-api/CLAUDE.md} mục 8. Nghĩa là mỗi lớp test cần nó phải viết lại đủ
 * mười lăm dòng đúng chằn chặn — và ở M4 đã có <b>bốn bản sao</b> của khối ấy.
 *
 * <p>Cái giá của bốn bản sao không phải số dòng: mỗi lần thêm một nhóm thuộc tính mới (như
 * {@code Auth} vừa rồi) là bốn chỗ phải sửa, và trình biên dịch chỉ chỉ ra chúng lần lượt,
 * mỗi lần một lỗi.
 *
 * <p>Các con số dưới đây khớp {@code application.yml} của production, cố ý: một test dựng
 * cấu hình khác production là một test trả lời câu hỏi không ai hỏi.
 */
public final class AppPropertiesGia {

    private AppPropertiesGia() {
    }

    public static AppProperties macDinh() {
        return voi(internalMacDinh(), authMacDinh(), submissionMacDinh());
    }

    /** Cho {@code InternalSecretFilterTest} — nó kiểm chính secret ấy. */
    public static AppProperties voiInternalSecret(String secret) {
        return voi(new AppProperties.Internal(secret), authMacDinh(), submissionMacDinh());
    }

    /** Cho {@code JwtTest} — nó đổi khoá ký và mốc thời gian. */
    public static AppProperties voiAuth(AuthProperties auth) {
        return voi(internalMacDinh(), auth, submissionMacDinh());
    }

    public static AuthProperties authMacDinh() {
        return new AuthProperties("k".repeat(32), Duration.ofMinutes(15), Duration.ofDays(7),
                12, 5, Duration.ofSeconds(60), Duration.ofMinutes(15));
    }

    private static AppProperties.Submission submissionMacDinh() {
        return new AppProperties.Submission(65_536, Duration.ofSeconds(10));
    }

    private static AppProperties.Internal internalMacDinh() {
        return new AppProperties.Internal("x".repeat(32));
    }

    private static AppProperties voi(AppProperties.Internal internal,
                                     AuthProperties auth,
                                     AppProperties.Submission submission) {
        return new AppProperties(
                submission,
                new AppProperties.Judge(Duration.ofSeconds(120), Duration.ofSeconds(15),
                        2, 20, "mac-m1max-host", Duration.ofMinutes(30), 5.0,
                        Duration.ofSeconds(10),
                        new AppProperties.Rejudge(2, Duration.ofSeconds(5), 200)),
                new AppProperties.Page(20, 50),
                internal,
                new AppProperties.Sse(Duration.ofMinutes(5), Duration.ofSeconds(15)),
                auth,
                new AppProperties.Jobs(Duration.ofSeconds(120), Duration.ofSeconds(5)),
                new ContestProperties(Duration.ofSeconds(2), 500,
                        Duration.ofMinutes(5), 50),
                new AppProperties.Ai(5, Duration.ofSeconds(30)));
    }
}
