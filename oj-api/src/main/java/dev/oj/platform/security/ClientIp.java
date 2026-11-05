package dev.oj.platform.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.regex.Pattern;

public final class ClientIp {

    private static final String CF = "CF-Connecting-IP";

    private static final String XFF = "X-Forwarded-For";

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    static final int TIEN_TO_IPV6 = 64;

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

    private static String cuoiCung(String header) {
        if (header == null) {
            return null;
        }
        int dauPhay = header.lastIndexOf(',');
        return (dauPhay < 0 ? header : header.substring(dauPhay + 1)).trim();
    }

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

    public static String khoaGioiHan(HttpServletRequest request) {
        return khoaGioiHan(cua(request));
    }

    private static boolean laLoopback(String ip) {
        return "127.0.0.1".equals(ip) || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip);
    }
}
