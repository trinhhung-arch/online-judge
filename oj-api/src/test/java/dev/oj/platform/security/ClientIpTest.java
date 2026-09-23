package dev.oj.platform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ClientIp#khoaGioiHan} — khoá dùng để đếm (rà soát 2026-09-24, F2) — và phép kiểm
 * "chỉ nhận IP dạng số" của {@code hopLe}.
 */
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

    /**
     * ★ Bản cũ lọc theo KÝ TỰ (số, chấm, hai chấm, chữ hex) — {@code bad.cafe} qua được, bị
     * {@code getByName} TRA DNS (đo 211ms, ra một IPv4 công khai) và được nhận là "IP".
     */
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
}
