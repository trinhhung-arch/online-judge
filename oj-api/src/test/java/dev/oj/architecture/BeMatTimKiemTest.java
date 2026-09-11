package dev.oj.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ robots.txt và sitemap.xml phải cho Google đọc được trang công khai — và chỉ trang công khai.
 *
 * <h2>Lỗi mà file này sinh ra để bắt — đo thật 2026-09-11</h2>
 * Ngày đầu có tên miền, {@code robots.txt} vẫn là bản chép từ DMOJ với {@code Disallow: /api/}.
 * DMOJ dựng trang ở server nên dòng ấy không làm họ mất gì. Ở đây {@code index.html},
 * {@code problem.html}, {@code contests.html} là vỏ rỗng, nội dung nạp qua {@code /api/v1/...}
 * — nên chính dòng ấy chặn nội dung của những trang mà file tự ghi là "cho index". Tệ hơn trang
 * trắng: {@code problem.js} gom tải đề và tải ngôn ngữ vào một {@code Promise.all}, nên chặn
 * {@code /languages} là Googlebot thấy câu "Không tải được đề bài."
 *
 * <p>Không có gì báo lỗi. Người thật dùng trang bình thường; trang chỉ không bao giờ lên Google.
 *
 * <h2>Hai chiều, giữ cả hai</h2>
 * Mở đủ cho trang công khai, chặn đủ cho bề mặt riêng tư. Chiều thứ hai KHÔNG phải bảo mật —
 * robots.txt không chặn ai, quyền thật nằm ở use-case. Nó chỉ để Google khỏi đưa trang riêng tư
 * lên kết quả tìm kiếm.
 *
 * <h2>Giới hạn phải biết</h2>
 * {@link #API_LUC_TAI} là bảng khai tay. Test đỏ khi {@code duong-dan.js} đổi tên một đường trong
 * bảng, nhưng KHÔNG tự thấy một trang vừa thêm lời gọi mới lúc tải. Thêm lời gọi thì sửa ba chỗ:
 * trang, dòng {@code Allow} trong robots.txt, và bảng này.
 *
 * <h2>Ngữ nghĩa so khớp</h2>
 * Như Google (RFC 9309): luật khớp DÀI NHẤT thắng, hoà thì {@code Allow} thắng, {@code *} là chuỗi
 * bất kỳ kể cả dấu {@code /}, {@code $} ở cuối là phải hết URL. Bộ so khớp sai thì mọi ca ở đây vô
 * nghĩa, nên nó có ca kiểm riêng.
 */
class BeMatTimKiemTest {

    private static final Path GOC = Path.of("src/main/resources/static");

    private static final String NS_SITEMAP = "http://www.sitemaps.org/schemas/sitemap/0.9";

    /** Một lời gọi mà trang cho index cần lúc tải, kèm chuỗi khai báo nó trong {@code duong-dan.js}. */
    private record LoiGoi(String trang, String khaiBao, String urlMau) {
    }

    private static final List<LoiGoi> API_LUC_TAI = List.of(
            new LoiGoi("index.html", "url: '/api/v1/problems'", "/api/v1/problems"),
            new LoiGoi("index.html", "url: '/api/v1/problems'", "/api/v1/problems?size=20&cursor=9"),
            new LoiGoi("problem.html", "`/api/v1/problems/${encodeURIComponent(ma)}`",
                    "/api/v1/problems/A-PLUS-B"),
            new LoiGoi("problem.html", "'/api/v1/languages'", "/api/v1/languages"),
            new LoiGoi("problem.html", "'/api/v1/status'", "/api/v1/status"),
            new LoiGoi("contests.html", "url: '/api/v1/contests'", "/api/v1/contests?size=20"),
            new LoiGoi("trang-thai.html", "'/api/v1/status'", "/api/v1/status"),
            new LoiGoi("login.html", "'/api/v1/auth/captcha'", "/api/v1/auth/captcha"));

    /** Bề mặt riêng tư. Hai nhóm giữa cố ý CHUNG TIỀN TỐ với đường đã mở — chỗ dễ mở lố nhất. */
    private static final List<String> PHAI_CHAN = List.of(
            "/internal/judge/claim", "/internal/judge/testdata/ab12",
            "/api/v1/me", "/api/v1/me/2fa", "/api/v1/jobs",
            "/api/v1/submissions", "/api/v1/submissions/42", "/api/v1/submissions/42/stream",
            "/api/v1/problems/12/edit", "/api/v1/problems/12/testdata", "/api/v1/problems/12/publish",
            "/api/v1/contests/ky-thi-thang-9", "/api/v1/contests/7/standings",
            "/api/v1/contests/7/standings/stream",
            "/api/v1/admin/ops", "/api/v1/admin/users", "/api/v1/auth/login", "/api/v1/auth/register",
            "/bai-nop.html", "/submission.html?id=1", "/ho-so.html", "/quan-tri.html",
            "/ra-de.html", "/nhat-ky.html", "/contest.html?slug=x");

    /** Cho index nhưng không vào được sitemap: cần tham số, Google tới qua liên kết trên trang chủ. */
    private static final Set<String> CAN_THAM_SO = Set.of("problem.html");

    @Test
    @DisplayName("★ trang cho index gọi được MỌI API nó cần lúc tải")
    void trang_cho_index_goi_duoc_api_luc_tai() throws IOException {
        Robots robots = Robots.doc(GOC.resolve("robots.txt"));
        List<String> biChan = API_LUC_TAI.stream()
                .filter(g -> !robots.choPhep(g.urlMau()))
                .map(g -> g.trang() + " cần " + g.urlMau())
                .toList();

        assertThat(biChan)
                .as("robots.txt chặn API mà trang công khai cần để hiện nội dung. Người thật vẫn thấy "
                        + "trang; Googlebot thấy vỏ rỗng hoặc câu báo lỗi. Thêm dòng Allow tương ứng")
                .isEmpty();
    }

    @Test
    @DisplayName("★ bề mặt riêng tư vẫn bị chặn — kể cả đường chung tiền tố với đường đã mở")
    void be_mat_rieng_tu_van_bi_chan() throws IOException {
        Robots robots = Robots.doc(GOC.resolve("robots.txt"));

        assertThat(PHAI_CHAN.stream().filter(robots::choPhep).toList())
                .as("robots.txt mở đường riêng tư cho Google. Use-case vẫn chặn người đọc, nhưng "
                        + "Google sẽ đưa những URL này lên kết quả tìm kiếm")
                .isEmpty();
    }

    @Test
    @DisplayName("bảng API lúc tải vẫn khớp duong-dan.js — frontend đổi đường thì đỏ ở đây trước")
    void bang_api_luc_tai_khop_duong_dan_js() throws IOException {
        String khaiBao = Files.readString(GOC.resolve("js/duong-dan.js"));

        assertThat(API_LUC_TAI.stream().map(LoiGoi::khaiBao).distinct()
                .filter(k -> !khaiBao.contains(k)).toList())
                .as("duong-dan.js không còn khai những đường này — trang đã đổi lời gọi. Sửa dòng "
                        + "Allow trong robots.txt và bảng API_LUC_TAI cho khớp")
                .isEmpty();
    }

    @Test
    @DisplayName("★ mọi trang .html hoặc bị chặn, hoặc nằm trong sitemap — không trang nào lửng lơ")
    void moi_trang_hoac_bi_chan_hoac_trong_sitemap() throws Exception {
        Robots robots = Robots.doc(GOC.resolve("robots.txt"));
        Set<String> trongSitemap = locTrongSitemap().stream().map(URI::getPath).collect(Collectors.toSet());
        List<String> lungLo = new ArrayList<>();
        try (Stream<Path> tep = Files.list(GOC)) {
            for (Path p : tep.filter(f -> f.toString().endsWith(".html")).sorted().toList()) {
                String ten = p.getFileName().toString();
                String duong = ten.equals("index.html") ? "/" : "/" + ten;
                if (robots.choPhep(duong) && !trongSitemap.contains(duong) && !CAN_THAM_SO.contains(ten)) {
                    lungLo.add(ten);
                }
            }
        }

        assertThat(lungLo)
                .as("Trang mà robots.txt không chặn và sitemap không có. Công khai thì thêm vào "
                        + "sitemap.xml; riêng tư thì thêm Disallow. Đừng để Google tự quyết")
                .isEmpty();
    }

    @Test
    @DisplayName("★ sitemap chỉ chứa trang có thật, được phép, cùng tên miền với dòng Sitemap")
    void sitemap_chi_chua_trang_hop_le() throws Exception {
        Robots robots = Robots.doc(GOC.resolve("robots.txt"));
        assertThat(robots.sitemaps()).as("robots.txt phải có đúng một dòng Sitemap").hasSize(1);
        URI khai = URI.create(robots.sitemaps().get(0));
        assertThat(khai.getScheme()).isEqualTo("https");
        assertThat(khai.getPath()).isEqualTo("/sitemap.xml");

        List<URI> locs = locTrongSitemap();
        assertThat(locs).as("sitemap.xml không có <loc> nào").isNotEmpty();
        List<String> loi = new ArrayList<>();
        for (URI u : locs) {
            String tep = u.getPath().equals("/") ? "index.html" : u.getPath().substring(1);
            if (!"https".equals(u.getScheme()) || !khai.getHost().equals(u.getHost())) {
                loi.add(u + " — khác https hoặc khác tên miền với dòng Sitemap");
            }
            if (!robots.choPhep(u.getPath())) {
                loi.add(u + " — robots.txt chặn trang này");
            }
            if (!Files.isRegularFile(GOC.resolve(tep))) {
                loi.add(u + " — không có file static/" + tep);
            }
        }
        assertThat(loi).isEmpty();
    }

    @Test
    @DisplayName("bộ so khớp theo đúng ngữ nghĩa Google — sai ở đây thì mọi ca trên vô nghĩa")
    void bo_so_khop_dung_ngu_nghia_google() {
        Robots r = Robots.doc(List.of(
                "User-agent: Bot-Khac", "Disallow: /",
                "User-agent: *",
                "Disallow: /a/", "Allow: /a/b", "Disallow: /a/b/*/",
                "Disallow: /x", "Allow: /x$",
                "Disallow: /p", "Allow: /p"));

        assertThat(r.choPhep("/khac")).as("nhóm của bot khác không áp cho *").isTrue();
        assertThat(r.choPhep("/a/c")).isFalse();
        assertThat(r.choPhep("/a/b1")).as("Allow dài hơn thắng").isTrue();
        assertThat(r.choPhep("/a/b/1/edit")).as("* khớp cả dấu /, Disallow dài hơn thắng").isFalse();
        assertThat(r.choPhep("/x")).as("$ khớp khi hết URL").isTrue();
        assertThat(r.choPhep("/x?y=1")).as("$ không khớp khi còn query").isFalse();
        assertThat(r.choPhep("/p")).as("hoà độ dài thì Allow thắng").isTrue();
    }

    private static List<URI> locTrongSitemap() throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        NodeList loc = f.newDocumentBuilder().parse(GOC.resolve("sitemap.xml").toFile())
                .getElementsByTagNameNS(NS_SITEMAP, "loc");
        List<URI> ketQua = new ArrayList<>();
        for (int i = 0; i < loc.getLength(); i++) {
            ketQua.add(URI.create(loc.item(i).getTextContent().trim()));
        }
        return ketQua;
    }

    /** Nhóm {@code User-agent: *} của một robots.txt, cộng mọi dòng Sitemap. */
    private record Robots(List<Luat> luat, List<String> sitemaps) {

        static Robots doc(Path tep) throws IOException {
            return doc(Files.readAllLines(tep));
        }

        static Robots doc(List<String> dong) {
            List<Luat> luat = new ArrayList<>();
            List<String> sitemaps = new ArrayList<>();
            boolean nhomSao = false;
            boolean dangGhepTenBot = false;
            for (String tho : dong) {
                String d = tho.replaceFirst("#.*", "").trim();
                int haiCham = d.indexOf(':');
                if (haiCham < 0) {
                    continue;
                }
                String khoa = d.substring(0, haiCham).trim().toLowerCase(Locale.ROOT);
                String giaTri = d.substring(haiCham + 1).trim();
                switch (khoa) {
                    case "user-agent" -> {
                        // Nhiều dòng User-agent liền nhau là MỘT nhóm; dòng luật đầu tiên đóng nhóm lại.
                        nhomSao = (dangGhepTenBot && nhomSao) || giaTri.equals("*");
                        dangGhepTenBot = true;
                    }
                    case "allow", "disallow" -> {
                        dangGhepTenBot = false;
                        if (nhomSao && !giaTri.isEmpty()) {
                            luat.add(Luat.tu(khoa.equals("allow"), giaTri));
                        }
                    }
                    case "sitemap" -> sitemaps.add(giaTri);
                    default -> { }
                }
            }
            return new Robots(luat, sitemaps);
        }

        boolean choPhep(String url) {
            Luat thang = null;
            for (Luat l : luat) {
                if (l.khop(url) && (thang == null || l.uuTienHon(thang))) {
                    thang = l;
                }
            }
            return thang == null || thang.choPhep();
        }
    }

    private record Luat(boolean choPhep, String mau, Pattern bieuThuc) {

        static Luat tu(boolean choPhep, String mau) {
            boolean neoCuoi = mau.endsWith("$");
            String than = neoCuoi ? mau.substring(0, mau.length() - 1) : mau;
            String re = Arrays.stream(than.split("\\*", -1))
                    .map(Pattern::quote)
                    .collect(Collectors.joining(".*", "^", neoCuoi ? "$" : ""));
            return new Luat(choPhep, mau, Pattern.compile(re));
        }

        boolean khop(String url) {
            return bieuThuc.matcher(url).find();
        }

        /** Dài hơn là cụ thể hơn. Bằng nhau thì Allow thắng — Google chọn luật ít hạn chế hơn. */
        boolean uuTienHon(Luat khac) {
            return mau.length() > khac.mau.length()
                    || (mau.length() == khac.mau.length() && choPhep && !khac.choPhep);
        }
    }
}
