package dev.oj.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * IP thật của người gọi — thứ FR-AUTH-08 đếm khi khoá 5 lần đăng nhập sai / phút / IP.
 *
 * <h2>★ Vì sao {@code getRemoteAddr()} một mình là SAI, và sai theo hướng tệ nhất</h2>
 * Hệ thống này chạy sau Cloudflare Tunnel ({@code build-order.md} tuần 9). Với tunnel, mọi
 * request tới Tomcat đều đến từ {@code 127.0.0.1}. Nghĩa là nếu chỉ đọc
 * {@code getRemoteAddr()} thì <b>toàn bộ người dùng dùng chung một IP</b>, và năm lần gõ sai
 * mật khẩu của một người sẽ khoá đăng nhập của tất cả mọi người trong 15 phút.
 *
 * <p>Một biện pháp chống tấn công tự biến thành công cụ tấn công. Đó là hỏng nguy hiểm hơn
 * hẳn so với không có biện pháp nào.
 *
 * <h2>★ Vì sao tin header một cách vô điều kiện cũng SAI</h2>
 * {@code X-Forwarded-For} là một chuỗi client tự đặt. Tin nó không điều kiện nghĩa là người
 * dò mật khẩu chỉ cần đổi header sau mỗi lần thử là bộ đếm không bao giờ chạm ngưỡng —
 * FR-AUTH-08 còn nguyên trong mã nguồn nhưng không còn tác dụng.
 *
 * <h2>Cách đúng: chỉ tin header khi kết nối ĐẾN TỪ proxy</h2>
 * Nếu {@code getRemoteAddr()} là loopback thì request đi qua tunnel chạy trên chính máy này,
 * và header do tunnel đặt là đáng tin. Nếu không phải loopback thì người gọi đang nói chuyện
 * trực tiếp với Tomcat, và mọi header họ gửi là dữ liệu của họ — bỏ qua.
 *
 * <p>Ưu tiên {@code CF-Connecting-IP} vì Cloudflare <b>ghi đè</b> header này chứ không nối
 * thêm, nên nó không bị chèn giá trị giả. {@code X-Forwarded-For} thì được nối thêm, và phần
 * tử đầu tiên là thứ client gửi lên — nên chỉ dùng nó khi không có lựa chọn nào khác, và chỉ
 * lấy phần tử <b>cuối</b>, phần do proxy gần nhất ghi.
 */
public final class ClientIp {

    /** Cloudflare ghi đè header này ở mọi request đi qua nó. */
    private static final String CF = "CF-Connecting-IP";

    private static final String XFF = "X-Forwarded-For";

    /** Bốn số thập phân — xem {@link #hopLe}. Giá trị từng số do {@code getByName} kiểm. */
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Dải IPv6 coi là MỘT người gọi. Không phải ngưỡng để tinh chỉnh như các trần trong
     * {@code application.yml}: /64 là đơn vị cấp phát nhỏ nhất cho một đường mạng (RFC 6177),
     * nên nó là định nghĩa của "một người gọi", như 32 bit là định nghĩa của một IPv4. Rộng hơn
     * (/56) là gom nhầm nhiều hộ của cùng nhà mạng; hẹp hơn là mở lại đường xoay địa chỉ.
     */
    static final int TIEN_TO_IPV6 = 64;

    /**
     * Giá trị thay thế khi không xác định được IP nào — ví dụ khi use-case được gọi thẳng từ
     * một test, không qua HTTP. Cột {@code login_attempts.client_ip} là {@code NOT NULL}, và
     * một địa chỉ trong dải tài liệu (RFC 5737) nói rõ "đây không phải IP thật" hơn hẳn
     * {@code 0.0.0.0}.
     */
    public static final String KHONG_RO = "192.0.2.0";

    private ClientIp() {
    }

    public static String cua(HttpServletRequest request) {
        if (request == null) {
            return KHONG_RO;
        }
        String remote = request.getRemoteAddr();
        if (laLoopback(remote)) {
            String cf = hopLe(request.getHeader(CF));
            if (cf != null) {
                return cf;
            }
            String xff = hopLe(cuoiCung(request.getHeader(XFF)));
            if (xff != null) {
                return xff;
            }
        }
        String ip = hopLe(remote);
        return ip != null ? ip : KHONG_RO;
    }

    /** Phần tử cuối của {@code X-Forwarded-For} — phần do proxy gần nhất ghi, không phải client. */
    private static String cuoiCung(String header) {
        if (header == null) {
            return null;
        }
        int dauPhay = header.lastIndexOf(',');
        return (dauPhay < 0 ? header : header.substring(dauPhay + 1)).trim();
    }

    /**
     * Trả về {@code null} nếu không phải một địa chỉ IP <b>viết dạng số</b>.
     *
     * <p>{@link InetAddress#getByName} <b>tra DNS</b> với một chuỗi không phải IP — tức là một
     * header giả mạo sẽ biến thành một lượt tra DNS đi ra ngoài, ở mỗi request.
     *
     * <p>★ Bản trước chặn bằng phép kiểm KÝ TỰ (số, chấm, hai chấm, chữ hex) và javadoc khẳng
     * định thế là đủ. Không đủ: {@code a-f} cũng là chữ của tên miền. Đo 2026-09-24:
     * {@code bad.cafe} qua được phép kiểm, tra DNS mất 211ms và được nhận là "IP"; rồi nó đi vào
     * {@code CAST(:clientIp AS inet)}. Giờ đòi hình dạng: IPv6 PHẢI có {@code ':'} (Java không
     * bao giờ tra DNS cho chuỗi có hai chấm — đo cùng ngày), IPv4 PHẢI là bốn số thập phân.
     */
    private static String hopLe(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
            return null;
        }
        boolean v6 = ip.indexOf(':') >= 0;
        if (!v6 && !IPV4.matcher(ip).matches()) {
            return null;
        }
        for (int i = 0; i < ip.length(); i++) {
            char c = ip.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || c == ':'
                    || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == '%';
            if (!ok) {
                return null;
            }
        }
        try {
            InetAddress.getByName(ip);
            return ip;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /**
     * ★ Khoá dùng để ĐẾM (khoá đăng nhập, trần đăng ký, trần API ẩn danh) — KHÔNG dùng để ghi
     * nhật ký; nhật ký vẫn giữ địa chỉ đầy đủ.
     *
     * <p>IPv4 giữ nguyên. IPv6 gom về dải /{@value #TIEN_TO_IPV6}. Một đường mạng gia đình hay
     * di động được cấp nguyên một /64 và tự đổi địa chỉ trong dải ấy (địa chỉ tạm, RFC 8981).
     * Đếm theo từng địa chỉ thì mỗi lần đổi là một xô mới: "5 lần sai/phút/IP" thành vô hạn, và
     * mã TOTP sáu chữ số dò được trong vài giờ (rà soát 2026-09-24, F2). IPv4 viết dạng
     * {@code ::ffff:a.b.c.d} tự quy về {@code a.b.c.d} — Java làm việc ấy khi đọc chuỗi.
     *
     * @param ip địa chỉ đã qua {@link #cua} — luôn là IP dạng số, nên không có lượt tra DNS nào
     * @return dạng Postgres {@code inet} đọc được: {@code 1.2.3.4} hoặc {@code 2001:db8:1:2:0:0:0:0/64}
     */
    public static String khoaGioiHan(String ip) {
        String hl = hopLe(ip);
        if (hl == null) {
            return KHONG_RO;
        }
        try {
            InetAddress a = InetAddress.getByName(hl);
            if (a instanceof Inet6Address) {
                byte[] b = a.getAddress();
                Arrays.fill(b, TIEN_TO_IPV6 / 8, b.length, (byte) 0);
                return InetAddress.getByAddress(b).getHostAddress() + "/" + TIEN_TO_IPV6;
            }
            return a.getHostAddress();
        } catch (UnknownHostException e) {
            return KHONG_RO;
        }
    }

    /** {@link #khoaGioiHan} của {@link #cua} — cho bộ lọc, nơi có sẵn request. */
    public static String khoaGioiHan(HttpServletRequest request) {
        return khoaGioiHan(cua(request));
    }

    private static boolean laLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
