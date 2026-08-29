package dev.oj.problems.infrastructure;

import dev.oj.problems.application.port.TestdataRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Bảng {@code testdata_versions}, {@code testcases}, {@code sample_testcase_contents} (V2).
 * Pool {@code app} — job nạp dữ liệu là việc của phía API, không phải đường verdict.
 *
 * <p>Mọi câu đều {@code ON CONFLICT ... DO UPDATE}: xem javadoc của {@link TestdataRepository}
 * về vì sao idempotent là bắt buộc chứ không phải một sự cẩn thận thừa.
 */
@Repository
public class JdbcTestdataRepository implements TestdataRepository {

    private static final String PHIEN_BAN_KE_TIEP = """
            SELECT COALESCE(max(version), 0) + 1
              FROM testdata_versions
             WHERE problem_id = :problemId
            """;

    private static final String TAO_PHIEN_BAN = """
            INSERT INTO testdata_versions (problem_id, version, manifest_sha256,
                                           test_count, total_bytes, created_by)
            VALUES (:problemId, :version, :manifest, :testCount, :totalBytes, :createdBy)
            ON CONFLICT (problem_id, version) DO UPDATE
               SET manifest_sha256 = EXCLUDED.manifest_sha256,
                   test_count = EXCLUDED.test_count,
                   total_bytes = EXCLUDED.total_bytes
            """;

    private static final String THEM_TESTCASE = """
            INSERT INTO testcases (problem_id, testdata_version, ordinal, is_sample,
                                   input_sha256, output_sha256, input_bytes, output_bytes)
            VALUES (:problemId, :version, :ordinal, :laSample,
                    :inputSha, :outputSha, :inputBytes, :outputBytes)
            ON CONFLICT (problem_id, testdata_version, ordinal) DO UPDATE
               SET is_sample = EXCLUDED.is_sample,
                   input_sha256 = EXCLUDED.input_sha256,
                   output_sha256 = EXCLUDED.output_sha256,
                   input_bytes = EXCLUDED.input_bytes,
                   output_bytes = EXCLUDED.output_bytes
            RETURNING id
            """;

    private static final String THEM_NOI_DUNG_SAMPLE = """
            INSERT INTO sample_testcase_contents (testcase_id, input_text, output_text)
            VALUES (:testcaseId, :input, :output)
            ON CONFLICT (testcase_id) DO UPDATE
               SET input_text = EXCLUDED.input_text,
                   output_text = EXCLUDED.output_text
            """;

    private static final String KICH_HOAT = """
            UPDATE problems SET current_testdata_version = :version WHERE id = :problemId
            """;

    private final JdbcClient jdbc;

    public JdbcTestdataRepository(@Qualifier("appJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int phienBanKeTiep(long problemId) {
        return jdbc.sql(PHIEN_BAN_KE_TIEP).param("problemId", problemId)
                .query(Integer.class).single();
    }

    @Override
    public void taoPhienBan(long problemId, int version, String manifestSha256,
                            int testCount, long totalBytes, long createdBy) {
        jdbc.sql(TAO_PHIEN_BAN)
                .param("problemId", problemId)
                .param("version", version)
                .param("manifest", manifestSha256)
                .param("testCount", testCount)
                .param("totalBytes", totalBytes)
                .param("createdBy", createdBy)
                .update();
    }

    @Override
    public long themTestcase(long problemId, int version, int ordinal, boolean laSample,
                             String inputSha256, String outputSha256,
                             int inputBytes, int outputBytes) {
        return jdbc.sql(THEM_TESTCASE)
                .param("problemId", problemId)
                .param("version", version)
                .param("ordinal", ordinal)
                .param("laSample", laSample)
                .param("inputSha", inputSha256)
                .param("outputSha", outputSha256)
                .param("inputBytes", inputBytes)
                .param("outputBytes", outputBytes)
                .query(Long.class)
                .single();
    }

    @Override
    public void themNoiDungSample(long testcaseId, String input, String output) {
        jdbc.sql(THEM_NOI_DUNG_SAMPLE)
                .param("testcaseId", testcaseId)
                .param("input", input)
                .param("output", output)
                .update();
    }

    @Override
    public void kichHoatPhienBan(long problemId, int version) {
        jdbc.sql(KICH_HOAT).param("version", version).param("problemId", problemId).update();
    }
}
