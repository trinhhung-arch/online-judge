package dev.oj.it;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobsException;
import dev.oj.problems.application.TestdataImportJob;
import dev.oj.problems.application.ZipTestdataValidator;
import dev.oj.problems.application.port.TestdataRepository;
import dev.oj.problems.application.port.TestdataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ Nạp testdata đầu-tới-cuối trên Postgres thật — FR-PROB-03, 04. Bước 4.10.
 *
 * <h2>Ca quan trọng nhất là {@link #noi_dung_test_an_khong_vao_postgres()}</h2>
 * Bất biến #1 nói rằng nội dung testcase ẩn không được rời khỏi worker. Nửa đầu của lời hứa đó
 * là <b>nó không bao giờ vào Postgres</b>, và V2 ép điều ấy bằng một khoá ngoại tổng hợp
 * {@code (testcase_id, is_sample)} — nhưng một ràng buộc chỉ có tác dụng nếu đường ghi thật sự
 * đi qua nó. Ca này là chỗ kiểm điều đó với dữ liệu thật.
 *
 * <h2>Kho được thay bằng bản trong bộ nhớ, và có lý do</h2>
 * Phần đáng kiểm ở đây là <i>bộ kiểm ZIP + job + ba bảng của V2</i>. MinIO chỉ là một chỗ để
 * byte nằm; dựng thêm một container cho nó làm chậm cả bộ IT mà không chứng minh thêm được
 * bất biến nào. Bản thân {@code MinioTestdataStore} được kiểm bằng tay khi chạy thật.
 *
 * <p>Đổi lại, {@code JdbcTestdataRepository} chạy trên <b>Postgres thật</b> — đúng như
 * {@code CLAUDE.md} mục 6 đòi cho mọi repository.
 */
class TestdataImportIT extends PostgresIT {

    @Autowired ZipTestdataValidator validator;
    @Autowired TestdataRepository testdata;
    @Autowired AuditLog auditLog;

    private KhoTestdataTrongBoNho kho;
    private TestdataImportJob job;

    /** Nội dung của một test ẩn — chuỗi này KHÔNG được xuất hiện ở bất kỳ đâu trong Postgres. */
    private static final String BI_MAT = "9999999 8888888 BI-MAT-KHONG-DUOC-VAO-POSTGRES";

    @BeforeEach
    void dungJob() {
        kho = new KhoTestdataTrongBoNho();
        job = new TestdataImportJob(validator, kho, testdata, auditLog);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("★ nạp xong: 3 test, đề chuyển sang phiên bản mới, tiến độ chạy tới 100%")
    void nap_day_du() {
        var ctx = chay(goiChuan());

        assertThat(ctx.tongCuoi).isEqualTo(3);
        assertThat(ctx.daXongCuoi).isEqualTo(3);

        int version = jdbc.sql("SELECT current_testdata_version FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single();
        assertThat(version).isEqualTo(2);   // seed đã có version 1

        assertThat(jdbc.sql("SELECT count(*) FROM testcases "
                        + "WHERE problem_id = :id AND testdata_version = :v")
                .param("id", PROBLEM_ID).param("v", version).query(Integer.class).single())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★ BẤT BIẾN #1 — nội dung test ẩn không có mặt ở bất kỳ đâu trong Postgres")
    void noi_dung_test_an_khong_vao_postgres() {
        chay(goiChuan());

        // Test 1 là sample: nội dung của nó ĐƯỢC phép nằm ở sample_testcase_contents.
        // Lọc theo phiên bản 2 — phiên bản 1 là của dev-seed và cũng có một sample.
        assertThat(jdbc.sql("""
                        SELECT s.input_text FROM sample_testcase_contents s
                          JOIN testcases t ON t.id = s.testcase_id
                         WHERE t.testdata_version = 2
                        """).query(String.class).list())
                .singleElement().asString().contains("1 2");

        // Test 2 và 3 là ẩn. Chuỗi bí mật của chúng phải không tồn tại ở bất kỳ cột nào.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM sample_testcase_contents
                         WHERE input_text LIKE :mau OR output_text LIKE :mau
                        """)
                .param("mau", "%BI-MAT%").query(Integer.class).single())
                .describedAs("nội dung test ẩn lọt vào Postgres — bất biến #1 bị phá")
                .isZero();

        // Và bảng testcases chỉ có hash, không có cột nội dung nào cả.
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM information_schema.columns
                         WHERE table_name = 'testcases'
                           AND column_name IN ('input_text', 'output_text', 'content')
                        """).query(Integer.class).single())
                .describedAs("ai đó vừa thêm một cột nội dung vào `testcases`")
                .isZero();
    }

    @Test
    @DisplayName("★ chỉ test được đánh dấu sample mới có nội dung công khai")
    void chi_sample_moi_cong_khai() {
        chay(goiChuan());

        var samples = jdbc.sql("""
                SELECT t.ordinal FROM sample_testcase_contents s
                  JOIN testcases t ON t.id = s.testcase_id
                 WHERE t.testdata_version = 2
                """).query(Integer.class).list();

        assertThat(samples).containsExactly(1);
    }

    @Test
    @DisplayName("★ chạy lại KHÔNG tạo bản sao — điều kiện để Quy tắc 5 có nghĩa")
    void chay_lai_khong_tao_ban_sao() {
        var lanDau = chay(goiChuan());
        // Mô phỏng instance chết rồi job được nhặt lại: cùng cursor, chạy lại từ đầu.
        chayVoiViTri(goiChuan(), lanDau.viTriDaLuu);

        assertThat(jdbc.sql("SELECT count(*) FROM testcases WHERE problem_id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single())
                .describedAs("3 test của phiên bản mới + 3 test của seed")
                .isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM testdata_versions WHERE problem_id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single())
                .describedAs("chạy lại phải dùng lại phiên bản đã lưu, không tạo phiên bản mới")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("★ hỏng giữa chừng thì đề VẪN dùng phiên bản cũ")
    void hong_giua_chung_khong_kich_hoat() {
        var goiHong = goiChuan();
        goiHong.remove("tests/03.out");   // thiếu nửa cặp -> lỗi ở bước kiểm

        assertThatThrownBy(() -> chay(goiHong)).isInstanceOf(Exception.class);

        assertThat(jdbc.sql("SELECT current_testdata_version FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single())
                .describedAs("không một bài nộp nào được chấm bằng nửa bộ test")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("huỷ giữa chừng thì job dừng và đề không đổi phiên bản")
    void huy_giua_chung() {
        var ctx = new JobContextGia(luuGoi(goiChuan()), SETTER_ID);
        ctx.huy = true;

        assertThatThrownBy(() -> job.chay(ctx))
                .isInstanceOf(JobsException.class)
                .hasFieldOrPropertyWithValue("code", "job.da_bi_huy");

        assertThat(jdbc.sql("SELECT current_testdata_version FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single()).isEqualTo(1);
    }

    // =========================================================================

    private static Map<String, byte[]> goiChuan() {
        var m = new LinkedHashMap<String, byte[]>();
        m.put("problem.yaml", b("samples: [1]\n"));
        m.put("tests/01.in", b("1 2\n"));
        m.put("tests/01.out", b("3\n"));
        m.put("tests/02.in", b(BI_MAT + "\n"));
        m.put("tests/02.out", b("18888887\n"));
        m.put("tests/03.in", b("-5 5\n"));
        m.put("tests/03.out", b("0\n"));
        return m;
    }

    private JobContextGia chay(Map<String, byte[]> goi) {
        return chayVoiViTri(goi, Map.of());
    }

    private JobContextGia chayVoiViTri(Map<String, byte[]> goi, Map<String, Object> viTri) {
        var ctx = new JobContextGia(luuGoi(goi), SETTER_ID);
        ctx.viTriDaLuu = new HashMap<>(viTri);
        job.chay(ctx);
        return ctx;
    }

    private Map<String, Object> luuGoi(Map<String, byte[]> goi) {
        byte[] zip = zip(goi);
        String sha = bam(zip);
        kho.dat(sha, zip);
        return Map.of("problemId", PROBLEM_ID, "zipSha256", sha, "zipBytes", (long) zip.length);
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

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

    private static String bam(byte[] noiDung) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(noiDung));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
