package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Thứ trình duyệt được phép chạy — luật trên {@code static/**} (rà soát bảo mật 2026-09-24).
 *
 * <p>Access token nằm trong {@code localStorage}: mọi script chạy trong trang đều đọc được nó.
 * Nên câu hỏi "trang này nạp mã từ ĐÂU" là câu hỏi bảo mật, không phải câu hỏi hiệu năng.
 * Trước ngày này CodeMirror được {@code import()} động từ jsDelivr ({@code +esm}, dựng lúc phục
 * vụ, không gắn được SRI), và CSP phải mở cả host {@code cdn.jsdelivr.net} — F3. Giờ mọi thư
 * viện nằm ở {@code static/vendor/}, và các luật dưới đây giữ nó ở đó.
 */
class TaiNguyenGiaoDienTest {

    private static final Path STATIC = Path.of("src", "main", "resources", "static");
    private static final Path VENDOR = STATIC.resolve("vendor");

    /** Host ngoài DUY NHẤT được nạp tài nguyên: widget Turnstile ở trang đăng ký (FR-AUTH-01). */
    private static final Set<String> HOST_CHO_PHEP = Set.of("challenges.cloudflare.com");

    private static final Pattern URL_TUYET_DOI = Pattern.compile("[\"'`(]\\s*https?://([A-Za-z0-9.-]+)");
    /**
     * Đích của {@code import}/{@code from} TRÔNG NHƯ đường dẫn module: tuyệt đối, tương đối,
     * hoặc có giao thức. Bản đầu nhận mọi chuỗi sau chữ {@code from} — và bắt nhầm từ khoá
     * {@code "from"} trong ngữ pháp Python mà {@code lang-python} mang theo làm DỮ LIỆU.
     */
    private static final Pattern IMPORT = Pattern.compile(
            "\\b(?:import|from)\\s*\\(?\\s*[\"'`]((?:https?:)?//[^\"'`]+|\\.{0,2}/[^\"'`]+)[\"'`]");

    @Test
    @DisplayName("★ HTML/JS/CSS của ta không trỏ ra host nào ngoài danh sách cho phép")
    void khong_host_ngoai() throws IOException {
        List<String> vi = new ArrayList<>();
        for (Path f : tep(STATIC, ".html", ".js", ".css")) {
            if (f.startsWith(VENDOR)) {
                continue;                 // vendor có luật riêng ở dưới
            }
            Matcher m = URL_TUYET_DOI.matcher(Files.readString(f));
            while (m.find()) {
                if (!HOST_CHO_PHEP.contains(m.group(1))) {
                    vi.add(STATIC.relativize(f) + " → " + m.group(1));
                }
            }
        }
        assertThat(vi).as("nạp mã từ host ngoài là trao localStorage (access token) cho host ấy")
                .isEmpty();
    }

    @Test
    @DisplayName("★ module trong vendor chỉ import từ /vendor/ — không đường nào vòng ra CDN")
    void vendor_chi_import_noi_bo() throws IOException {
        List<String> vi = new ArrayList<>();
        for (Path f : tep(VENDOR, ".js")) {
            Matcher m = IMPORT.matcher(Files.readString(f));
            while (m.find()) {
                String dich = m.group(1);
                if (!dich.startsWith("/vendor/") && !dich.startsWith("./")) {
                    vi.add(VENDOR.relativize(f) + " → " + dich);
                }
            }
        }
        assertThat(tep(VENDOR, ".js")).as("không có file vendor nào thì luật này không đo gì")
                .hasSizeGreaterThan(40);
        assertThat(vi).isEmpty();
    }

    /**
     * Vendor là mã của người khác nằm trong repo của ta. Sửa tay một file ở đây (vá nhanh, gỡ
     * lỗi) mà không ghi lại là mất dấu nó khác bản gốc — và bản gốc là thứ đã được xem xét.
     */
    @Test
    @DisplayName("★ static/vendor/SHA256SUMS khớp TỪNG file — sửa vendor mà không ghi lại là đỏ")
    void vendor_khop_sha256() throws Exception {
        Map<String, String> ghi = new TreeMap<>();
        for (String dong : Files.readAllLines(VENDOR.resolve("SHA256SUMS"))) {
            if (!dong.isBlank() && !dong.startsWith("#")) {
                String[] p = dong.trim().split("\\s+", 2);
                ghi.put(p[1], p[0]);
            }
        }
        Map<String, String> that = new TreeMap<>();
        for (Path f : tep(VENDOR, "")) {
            if (!f.getFileName().toString().equals("SHA256SUMS")) {
                that.put(VENDOR.relativize(f).toString().replace('\\', '/'), sha256(f));
            }
        }
        assertThat(that).as("thêm/bớt/sửa file vendor thì phải cập nhật SHA256SUMS").isEqualTo(ghi);
    }

    /**
     * Chuỗi CSP nằm trong {@code application.yml}, không phải trong mã — nên phải đọc chính file
     * ấy. Nới lại {@code script-src} cho một CDN là mở lại F3 dù {@code static/} vẫn sạch.
     */
    @Test
    @DisplayName("★ CSP: script-src chỉ 'self' + Turnstile, không còn jsdelivr ở đâu")
    void csp_khong_mo_cdn() throws IOException {
        String yml = Files.readString(Path.of("src", "main", "resources", "application.yml"));
        Matcher m = Pattern.compile("content-security-policy: >-\\n((?: {6}.*\\n)+)").matcher(yml);
        assertThat(m.find()).as("không tìm thấy khối content-security-policy").isTrue();
        String csp = m.group(1);

        assertThat(csp).contains("script-src 'self' https://challenges.cloudflare.com;")
                .doesNotContain("jsdelivr").doesNotContain("'unsafe-inline';\n      script")
                .contains("font-src 'self';").contains("style-src 'self';");
    }

    /** Lỗ 4 của bao-mat-plan: lời hứa "một innerHTML duy nhất" giờ là một luật. */
    @Test
    @DisplayName("★ đúng MỘT phép gán innerHTML, ở problem.js, gán statementHtml do server render")
    void mot_inner_html_duy_nhat() throws IOException {
        Pattern gan = Pattern.compile("\\.(innerHTML|outerHTML)\\s*\\+?=|insertAdjacentHTML\\s*\\(");
        List<String> co = new ArrayList<>();
        for (Path f : tep(STATIC.resolve("js"), ".js")) {
            String[] dong = boChuThich(Files.readString(f)).split("\n");
            for (String d : dong) {
                if (gan.matcher(d).find()) {
                    co.add(f.getFileName() + ": " + d.trim());
                }
            }
        }
        assertThat(co).containsExactly("problem.js: khung.innerHTML = de.statementHtml;");
    }

    private static List<Path> tep(Path goc, String... duoi) throws IOException {
        try (Stream<Path> s = Files.walk(goc)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> duoi.length == 1 && duoi[0].isEmpty()
                            || Stream.of(duoi).anyMatch(d -> p.toString().endsWith(d)))
                    .sorted().toList();
        }
    }

    private static String sha256(Path f) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
    }

    /** Bỏ chú thích {@code //…} và {@code /* … *}{@code /} — javadoc của ta nhắc innerHTML nhiều lần. */
    private static String boChuThich(String ma) {
        return ma.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
    }
}
