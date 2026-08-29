package dev.oj.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;

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
     * Trả về {@code null} nếu không phải một địa chỉ IP.
     *
     * <p>{@link InetAddress#getByName} <b>tra DNS</b> với một chuỗi không phải IP — tức là một
     * header giả mạo sẽ biến thành một lượt tra DNS đi ra ngoài, ở mỗi request. Nên phải chặn
     * trước bằng một phép kiểm ký tự: IP chỉ gồm chữ số, dấu chấm, hai chấm và chữ cái hex.
     */
    private static String hopLe(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
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

    private static boolean laLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
