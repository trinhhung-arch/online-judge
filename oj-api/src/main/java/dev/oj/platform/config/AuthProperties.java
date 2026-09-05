package dev.oj.platform.config;

import java.time.Duration;

/**
 * Danh tính — Bước 4.5, FR-AUTH-02 và FR-AUTH-08.
 *
 * <h2>Bốn con số ở đây KHÔNG được đổi mà không hỏi người</h2>
 * {@code access-ttl} 15 phút và {@code refresh-ttl} 7 ngày là chữ của FR-AUTH-02;
 * {@code max-login-failures} 5 và {@code lockout} 15 phút là hai dòng trong bảng giới hạn
 * của {@code oj-api/CLAUDE.md} mục 8, mà mục đó kết thúc bằng đúng câu <i>"đổi bất kỳ con
 * số nào ở bảng này là phải hỏi người"</i>. Các compact constructor dưới đây <b>crash lúc
 * boot</b> nếu ai đó lặng lẽ nới chúng ra.
 *
 * <h2>Vì sao {@code access-ttl} ngắn là điều kiện để thiết kế này đúng</h2>
 * Access token <b>không tra cứu database</b> — vai trò nằm ngay trong token
 * ({@code CurrentUserProvider.CurrentUser}). Đó là thứ làm nó rẻ, và cũng là thứ làm nó
 * <i>cũ</i>: hạ vai trò một người từ ADMIN xuống USER thì token cũ vẫn còn ADMIN cho tới
 * khi hết hạn. Mười lăm phút là trần của khoảng cũ đó. Kéo dài ra để "đỡ phải refresh"
 * là kéo dài đúng khoảng thời gian ấy.
 *
 * @param jwtSecret        khoá HMAC-SHA256. Đọc từ env, không có mặc định, tối thiểu 32 ký tự
 * @param accessTtl        FR-AUTH-02 — 15 phút
 * @param refreshTtl       FR-AUTH-02 — 7 ngày
 * @param bcryptCost       FR-AUTH-01 — 12. Khớp comment trên {@code users.password_hash}
 * @param maxLoginFailures FR-AUTH-08 — 5 lần sai
 * @param loginWindow      FR-AUTH-08 — trong 1 phút
 * @param lockout          FR-AUTH-08 — khoá 15 phút
 *
 * <h2>★ Hai con số đăng ký KHÔNG nằm trong bảng đã chốt — chúng mới, và chúng phải chỉnh được</h2>
 * Bảy con số phía trên là hợp đồng: đổi là phải hỏi người. Hai con số dưới đây thì ngược lại,
 * và lý do rất cụ thể: <b>một lớp học ngồi sau cùng một NAT là một IP duy nhất</b>. Ba mươi
 * học sinh đăng ký trong mười phút là hành vi hoàn toàn bình thường và nhìn giống hệt một bot.
 *
 * <p>Không có con số nào đúng cho cả hai tình huống. Mặc định 10 lượt/giờ chặn được việc tạo
 * tài khoản hàng loạt mà không phiền người dùng lẻ; buổi nào có lớp thì nâng bằng biến môi
 * trường {@code OJ_MAX_REGISTRATIONS_PER_IP} rồi hạ lại. Đó là một quyết định vận hành, không
 * phải một hằng số của hệ thống.
 *
 * @param maxRegistrationsPerIp số tài khoản tối đa tạo được từ một IP trong một cửa sổ
 * @param registrationWindow    độ dài cửa sổ ấy
 *
 * <h2>★ Hai con số của xác thực hai lớp</h2>
 * {@code totpKey} là khoá AES-256 mã hoá bí mật TOTP lúc lưu. Nó RIÊNG, không dùng chung
 * với {@code jwtSecret}: xoay khoá ký token là việc vệ sinh nên làm định kỳ, mà dùng chung
 * thì mỗi lần xoay sẽ làm hỏng đăng ký 2FA của tất cả mọi người.
 *
 * <p>{@code requireAdminTwoFactor} mặc định BẬT. Tài khoản ADMIN đọc được testdata mọi đề,
 * nên một mật khẩu ADMIN bị lộ là mất toàn bộ bộ test — tức là mất chính thứ hệ thống này
 * bán. DMOJ đặt {@code DMOJ_REQUIRE_STAFF_2FA = True} mặc định vì cùng lý do.
 *
 * @param totpKey               khoá mã hoá bí mật TOTP. Đọc từ env, tối thiểu 32 ký tự
 * @param requireAdminTwoFactor ép ADMIN phải bật 2FA mới dùng được quyền ADMIN
 *
 * <h2>★ Hai con số chống làm nghẽn CPU bằng chính đường đăng nhập</h2>
 * BCrypt cost 12 tốn ~250ms CPU mỗi lần, và {@code FR-AUTH-08} chỉ khoá các lượt <b>SAI</b>.
 * Một bot có 1 000 tài khoản hợp lệ chỉ cần đăng nhập ĐÚNG liên tục là ăn hết CPU — nó không
 * phải đoán gì cả, vì chính nó đặt mật khẩu lúc đăng ký. Đo trên máy chấm chuẩn ngày
 * 2026-09-05: <b>17,5 lượt/giây là trần của cả máy</b>, và lúc ấy chấm bài đứng.
 *
 * <p>{@code bcryptConcurrency} là trần CỨNG cho số lần băm chạy song song. Vượt trần thì
 * request bị TỪ CHỐI NGAY (429) chứ không xếp hàng: xếp hàng nghĩa là giữ luồng Tomcat, và
 * 200 luồng bị giữ thì mọi endpoint khác cũng chết theo — đúng thứ hàng rào này sinh ra để
 * ngăn. Bỏ tải là cách duy nhất giữ cho đọc đề và chấm bài còn thở.
 *
 * <p>4 trên máy 8 P-core: đăng nhập không bao giờ chiếm quá nửa số core.
 *
 * @param bcryptConcurrency số lần băm BCrypt được chạy song song
 * @param bcryptWait        chờ tối đa ngần này để xin một suất, hết thì trả 429
 * @param loginMinInterval  khoảng cách tối thiểu giữa hai lượt đăng nhập THÀNH CÔNG của
 *                          cùng một tài khoản
 * @param turnstile         chống bot ở cửa đăng ký — xem {@link TurnstileProperties}
 */
public record AuthProperties(
        String jwtSecret,
        Duration accessTtl,
        Duration refreshTtl,
        int bcryptCost,
        int maxLoginFailures,
        Duration loginWindow,
        Duration lockout,
        int maxRegistrationsPerIp,
        Duration registrationWindow,
        String totpKey,
        boolean requireAdminTwoFactor,
        int bcryptConcurrency,
        Duration bcryptWait,
        Duration loginMinInterval,
        TurnstileProperties turnstile) {

public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu OJ_JWT_SECRET. Đây là khoá ký access token — chạy mà thiếu nó "
                            + "nghĩa là không phát được token nào, hoặc tệ hơn: phát bằng "
                            + "một khoá mặc định mà ai đọc mã nguồn cũng biết");
        }
        // HMAC-SHA256 sinh khoá 32 byte. Ngắn hơn thế thì phần entropy thiếu được bù bằng
        // padding của HMAC, tức là khoá yếu hơn thuật toán — hạ giá cả chữ ký lẫn token.
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException("OJ_JWT_SECRET quá ngắn (cần >= 32 ký tự)");
        }
        if (accessTtl == null || accessTtl.isZero() || accessTtl.isNegative()
                || accessTtl.toMinutes() > 15) {
            throw new IllegalStateException(
                    "oj.auth.access-ttl = " + accessTtl + ". FR-AUTH-02 chốt 15 phút, và đó "
                            + "là trần của khoảng thời gian một vai trò đã bị hạ vẫn còn "
                            + "hiệu lực. Kéo dài là phải hỏi người — CLAUDE.md mục 5.4");
        }
        if (refreshTtl == null || refreshTtl.compareTo(accessTtl) <= 0) {
            throw new IllegalStateException(
                    "oj.auth.refresh-ttl (" + refreshTtl + ") phải LỚN HƠN access-ttl ("
                            + accessTtl + ") — nếu không thì refresh token hết hạn trước "
                            + "thứ nó dùng để làm mới, và người dùng bị đăng xuất mỗi 15 phút");
        }
        if (bcryptCost != 12) {
            throw new IllegalStateException(
                    "oj.auth.bcrypt-cost = " + bcryptCost + ", nhưng FR-AUTH-01 và comment "
                            + "trên users.password_hash đều ghi 12. Hạ cost là làm yếu "
                            + "TOÀN BỘ mật khẩu đã băm trước đó vẫn còn trong database");
        }
        if (maxLoginFailures != 5 || lockout == null || lockout.toMinutes() != 15
                || loginWindow == null || loginWindow.toSeconds() != 60) {
            throw new IllegalStateException(
                    "oj.auth: FR-AUTH-08 chốt 5 lần sai / 1 phút / IP, khoá 15 phút. "
                            + "Nhận được " + maxLoginFailures + " lần / " + loginWindow
                            + " / khoá " + lockout + ". Đây là một dòng trong bảng giới hạn "
                            + "của oj-api/CLAUDE.md mục 8 — đổi là phải hỏi người");
        }
        if (maxRegistrationsPerIp < 1) {
            throw new IllegalStateException(
                    "oj.auth.max-registrations-per-ip = " + maxRegistrationsPerIp
                            + ". Nhỏ hơn 1 nghĩa là không ai đăng ký được — muốn đóng hẳn cửa "
                            + "đăng ký thì đó là một công tắc system_settings, không phải một "
                            + "giới hạn tốc độ đặt bằng 0");
        }
        if (registrationWindow == null || registrationWindow.isZero()
                || registrationWindow.isNegative()) {
            throw new IllegalStateException(
                    "oj.auth.registration-window = " + registrationWindow + " không hợp lệ");
        }
        if (totpKey == null || totpKey.isBlank()) {
            throw new IllegalStateException(
                    "Thiếu OJ_TOTP_KEY. Đây là khoá mã hoá bí mật TOTP lúc lưu — thiếu nó "
                            + "thì hoặc không bật được 2FA, hoặc bí mật nằm trần trong "
                            + "database. Cùng lý do với OJ_JWT_SECRET: không có mặc định");
        }
        if (totpKey.length() < 32) {
            throw new IllegalStateException("OJ_TOTP_KEY quá ngắn (cần >= 32 ký tự)");
        }
        if (bcryptConcurrency < 1) {
            throw new IllegalStateException(
                    "oj.auth.bcrypt-concurrency = " + bcryptConcurrency + ". Nhỏ hơn 1 nghĩa "
                            + "là không ai đăng nhập được");
        }
        if (bcryptWait == null || bcryptWait.isNegative()) {
            throw new IllegalStateException("oj.auth.bcrypt-wait không hợp lệ");
        }
        if (loginMinInterval == null || loginMinInterval.isNegative()) {
            throw new IllegalStateException("oj.auth.login-min-interval không hợp lệ");
        }
        if (turnstile == null) {
            throw new IllegalStateException("Thiếu khối oj.auth.turnstile");
        }
        if (totpKey.equals(jwtSecret)) {
            throw new IllegalStateException(
                    "OJ_TOTP_KEY trùng OJ_JWT_SECRET. Một khoá một việc: dùng chung thì mỗi "
                            + "lần xoay khoá ký token sẽ làm hỏng đăng ký 2FA của tất cả "
                            + "mọi người, biến một thao tác vệ sinh thành một sự cố");
        }
    }
}
