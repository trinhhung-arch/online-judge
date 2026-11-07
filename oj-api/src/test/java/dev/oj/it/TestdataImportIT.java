package dev.oj.it;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.jobs.JobParams;
import dev.oj.platform.jobs.JobType;
import dev.oj.platform.jobs.JobsException;
import dev.oj.problems.application.NghiaVuChamLai;
import dev.oj.problems.application.TestdataImportJob;
import dev.oj.problems.application.ZipTestdataValidator;
import dev.oj.problems.application.port.TestdataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestdataImportIT extends PostgresIT {

    @Autowired ZipTestdataValidator validator;
    @Autowired TestdataRepository testdata;
    @Autowired AuditLog auditLog;

    private KhoTestdataTrongBoNho kho;
    private TestdataImportJob job;

    private static final String BI_MAT = "9999999 8888888 BI-MAT-KHONG-DUOC-VAO-POSTGRES";

    @org.springframework.beans.factory.annotation.Autowired
    private dev.oj.platform.jobs.JobRepository jobs;

    @BeforeEach
    void dungJob() {
        kho = new KhoTestdataTrongBoNho();
        job = new TestdataImportJob(validator, kho, testdata, auditLog, new NghiaVuChamLai(jobs));
    }

    @Test
    @DisplayName("★ nạp xong: 3 test, đề chuyển sang phiên bản mới, tiến độ chạy tới 100%")
    void nap_day_du() {
        var ctx = chay(goiChuan());

        assertThat(ctx.tongCuoi).isEqualTo(3);
        assertThat(ctx.daXongCuoi).isEqualTo(3);

        int version = jdbc.sql("SELECT current_testdata_version FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single();
        assertThat(version).isEqualTo(2);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM testcases
                WHERE problem_id = :id AND testdata_version = :v
                """)
                .param("id", PROBLEM_ID).param("v", version).query(Integer.class).single())
                .isEqualTo(3);
    }

    @Test
    @DisplayName("★ BẤT BIẾN #1 — nội dung test ẩn không có mặt ở bất kỳ đâu trong Postgres")
    void noi_dung_test_an_khong_vao_postgres() {
        chay(goiChuan());

        assertThat(jdbc.sql("""
                        SELECT s.input_text FROM sample_testcase_contents s
                          JOIN testcases t ON t.id = s.testcase_id
                         WHERE t.testdata_version = 2
                        """).query(String.class).list())
                .singleElement().asString().contains("1 2");

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM sample_testcase_contents
                         WHERE input_text LIKE :mau OR output_text LIKE :mau
                        """)
                .param("mau", "%BI-MAT%").query(Integer.class).single())
                .describedAs("nội dung test ẩn lọt vào Postgres — bất biến #1 bị phá")
                .isZero();

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
        var lanSau = chayVoiViTri(goiChuan(), lanDau.viTriDaLuu);

        assertThat(jdbc.sql("SELECT count(*) FROM testcases WHERE problem_id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single())
                .describedAs("3 test của phiên bản mới + 3 test của seed")
                .isEqualTo(6);
        assertThat(jdbc.sql("SELECT count(*) FROM testdata_versions WHERE problem_id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single())
                .describedAs("chạy lại phải dùng lại phiên bản đã lưu, không tạo phiên bản mới")
                .isEqualTo(2);

        assertThat(lanSau.mucSuKien)
                .describedAs("chạy lại gặp job chấm lại của chính mình là chuyện bình thường")
                .doesNotContain("ERROR");
        assertThat(soJobChamLaiDangSong())
                .describedAs("chạy lại không được tạo thêm job chấm lại thứ hai")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ FR-PROB-10 · đã có job chấm lại tạo TRƯỚC khi đổi testdata → vẫn báo ERROR")
    void job_cham_lai_cu_van_bao_dong() {
        long cu = jobs.tao(JobType.REJUDGE, Map.of(JobParams.PROBLEM_ID, PROBLEM_ID), ADMIN_ID);

        var ctx = chay(goiChuan());

        assertThat(ctx.mucSuKien)
                .describedAs("job #" + cu + " có thể đã chấm một phần bài bằng bộ test cũ")
                .contains("ERROR");
        assertThat(String.join("\n", ctx.suKien)).contains("#" + cu);
        assertThat(jobs.dangSong(JobType.REJUDGE, String.valueOf(PROBLEM_ID)))
                .get().extracting(j -> j.id()).isEqualTo(cu);
    }

    private int soJobChamLaiDangSong() {
        return jdbc.sql("""
                        SELECT count(*) FROM jobs
                         WHERE type = 'REJUDGE' AND params->>'problemId' = :id
                           AND status IN ('PENDING','RUNNING','PAUSED')
                        """)
                .param("id", String.valueOf(PROBLEM_ID)).query(Integer.class).single();
    }

    @Test
    @DisplayName("★ hỏng giữa chừng thì đề VẪN dùng phiên bản cũ")
    void hong_giua_chung_khong_kich_hoat() {
        var goiHong = goiChuan();
        goiHong.remove("tests/03.out");

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
