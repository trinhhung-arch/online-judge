package dev.oj.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpTest {

    @Test
    @DisplayName("★ hai địa chỉ IPv6 cùng /64 → CÙNG một khoá; khác /64 → khác khoá")
    void ipv6_gom_theo_dai() {
        String a = ClientIp.khoaGioiHan("2001:db8:1:2:aaaa:bbbb:cccc:dddd");
        String b = ClientIp.khoaGioiHan("2001:db8:1:2::1");
        String khacDai = ClientIp.khoaGioiHan("2001:db8:1:3::1");

        assertThat(a).as("xoay địa chỉ trong dải không được ra xô mới").isEqualTo(b)
                .isEqualTo("2001:db8:1:2:0:0:0:0/64");
        assertThat(khacDai).isNotEqualTo(a);
    }

    @Test
    @DisplayName("IPv4 giữ nguyên; IPv4 viết dạng ::ffff:a.b.c.d quy về a.b.c.d")
    void ipv4_giu_nguyen() {
        assertThat(ClientIp.khoaGioiHan("203.0.113.7")).isEqualTo("203.0.113.7");
        assertThat(ClientIp.khoaGioiHan("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("chuỗi không phải IP → giá trị thay thế, không ném")
    void rac_thi_thay_the() {
        assertThat(ClientIp.khoaGioiHan("khong-phai-ip")).isEqualTo(ClientIp.KHONG_RO);
        assertThat(ClientIp.khoaGioiHan((String) null)).isEqualTo(ClientIp.KHONG_RO);
    }

    @Test
    @DisplayName("★ tên miền chỉ gồm chữ hex (bad.cafe) KHÔNG được nhận là IP — không tra DNS")
    void ten_mien_chu_hex_khong_phai_ip() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("CF-Connecting-IP", "bad.cafe");

        assertThat(ClientIp.cua(req)).as("header rác thì lùi về địa chỉ kết nối").isEqualTo("127.0.0.1");
        assertThat(ClientIp.khoaGioiHan("dead.beef")).isEqualTo(ClientIp.KHONG_RO);
    }

    @Test
    @DisplayName("qua tunnel (loopback) thì đếm theo CF-Connecting-IP, gom /64")
    void qua_tunnel_dem_theo_header() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("CF-Connecting-IP", "2001:db8:5:6:1:2:3:4");

        assertThat(ClientIp.khoaGioiHan(req)).isEqualTo("2001:db8:5:6:0:0:0:0/64");
    }

    @Test
    @DisplayName("★ KHÔNG qua tunnel mà mang CF-Connecting-IP / X-Forwarded-For giả → bỏ qua, đếm theo địa chỉ thật")
    void ngoai_tunnel_khong_tin_header() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("198.51.100.20");
        req.addHeader("CF-Connecting-IP", "203.0.113.99");
        req.addHeader("X-Forwarded-For", "203.0.113.98");

        assertThat(ClientIp.cua(req)).isEqualTo("198.51.100.20");
        assertThat(ClientIp.khoaGioiHan(req)).isEqualTo("198.51.100.20");
    }

    @Test
    @DisplayName("qua tunnel, không có CF-Connecting-IP, X-Forwarded-For nhiều chặng → lấy chặng CUỐI, không lấy chặng client tự khai")
    void xff_nhieu_chang_lay_chang_cuoi() {
        var req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.98, 198.51.100.7");

        assertThat(ClientIp.cua(req)).isEqualTo("198.51.100.7");
    }
}
