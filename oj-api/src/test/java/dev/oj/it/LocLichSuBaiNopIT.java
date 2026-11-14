package dev.oj.it;

import dev.oj.contract.Verdict;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionFilter;
import dev.oj.judging.application.port.SubmissionRepository.SubmissionListItem;
import dev.oj.judging.application.usecase.ListMySubmissionsUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class LocLichSuBaiNopIT extends PostgresIT {

    private static final int TRANG = 2;

    @Autowired ListMySubmissionsUseCase lichSu;

    private record Bai(long id, long problemId, Verdict verdict, int languageId) {
    }

    private final List<Bai> cuaDev = new ArrayList<>();
    private long deHai;
    private int cpp;
    private int py;

    @BeforeEach
    void taoDuLieu() {
        cpp = ngonNgu("cpp20");
        py = ngonNgu("py311");
        deHai = jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, output_limit_kb, checker_type,
                                      scoring_mode, feedback_level, status, owner_id,
                                      published_at, current_testdata_version)
                VALUES ('DE-HAI', 'Đề hai', 'x', :hash, 1000, 262144, 65536, 'token',
                        'ALL_OR_NOTHING', 'TEST_INDEX', 'PUBLISHED', :owner, now(), 1)
                RETURNING id
                """).param("hash", "c".repeat(64)).param("owner", SETTER_ID)
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size) VALUES
                (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """).param("sha", "a".repeat(64)).update();

        for (long de : List.of(PROBLEM_ID, deHai)) {
            for (Verdict v : List.of(Verdict.AC, Verdict.WA, Verdict.TLE)) {
                for (int ngonNgu : List.of(cpp, py)) {
                    cuaDev.add(new Bai(nop(USER_ID, de, v, ngonNgu), de, v, ngonNgu));
                }
            }
        }
        cuaDev.add(new Bai(nop(USER_ID, PROBLEM_ID, null, cpp), PROBLEM_ID, null, cpp));
        nop(SETTER_ID, PROBLEM_ID, Verdict.AC, cpp);
        long biAn = nop(USER_ID, PROBLEM_ID, Verdict.AC, cpp);
        jdbc.sql("UPDATE submissions SET hidden_at = now(), hidden_by = :admin WHERE id = :id")
                .param("admin", ADMIN_ID).param("id", biAn).update();
    }

    @Test
    @DisplayName("★ D10 · lọc theo đề / verdict / ngôn ngữ, từng cái và kết hợp — đi hết các trang")
    void loc_dung_qua_moi_trang() {
        kiem("theo đề", new SubmissionFilter(deHai, null, null), b -> b.problemId() == deHai);
        kiem("theo verdict", new SubmissionFilter(null, Verdict.WA, null),
                b -> b.verdict() == Verdict.WA);
        kiem("theo ngôn ngữ", new SubmissionFilter(null, null, py), b -> b.languageId() == py);
        kiem("cả ba", new SubmissionFilter(PROBLEM_ID, Verdict.AC, cpp),
                b -> b.problemId() == PROBLEM_ID && b.verdict() == Verdict.AC
                        && b.languageId() == cpp);
        kiem("không lọc", SubmissionFilter.none(), b -> true);
    }

    @Test
    @DisplayName("bộ lọc không khớp bài nào → trang rỗng, không có cursor tiếp")
    void loc_khong_khop_thi_rong() {
        var trang = lichSu.list(null, TRANG, new SubmissionFilter(deHai, Verdict.MLE, null));

        assertThat(trang.items()).isEmpty();
        assertThat(trang.nextCursor()).isNull();
    }

    private void kiem(String ca, SubmissionFilter loc, Predicate<Bai> khop) {
        List<Long> kyVong = cuaDev.stream().filter(khop).map(Bai::id)
                .sorted(Comparator.reverseOrder()).toList();

        List<Long> nhanDuoc = new ArrayList<>();
        String cursor = null;
        for (int luot = 0; luot <= cuaDev.size(); luot++) {
            var trang = lichSu.list(cursor, TRANG, loc);
            assertThat(trang.items()).as("ca '%s'", ca).hasSizeLessThanOrEqualTo(TRANG);
            trang.items().stream().map(SubmissionListItem::id).forEach(nhanDuoc::add);
            cursor = trang.nextCursor();
            if (cursor == null) {
                break;
            }
        }

        assertThat(nhanDuoc)
                .describedAs("ca '%s' — đúng bài, mới trước, không sót, không trùng giữa các trang;"
                        + " không có bài người khác và bài đã ẩn", ca)
                .containsExactlyElementsOf(kyVong);
    }

    private long nop(long userId, long problemId, Verdict verdict, int languageId) {
        return jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, language_id, source_sha256,
                                         source_bytes, testdata_version, status, attempt,
                                         verdict, score, max_score, judged_at)
                VALUES (:u, :p, :lang, :sha, 12, 1,
                        CASE WHEN :v::TEXT IS NULL THEN 'QUEUED' ELSE 'DONE' END,
                        CASE WHEN :v::TEXT IS NULL THEN 0 ELSE 1 END,
                        :v, 0, 100,
                        CASE WHEN :v::TEXT IS NULL THEN NULL ELSE now() END)
                RETURNING id
                """)
                .param("u", userId).param("p", problemId).param("lang", languageId)
                .param("sha", "a".repeat(64))
                .param("v", verdict == null ? null : verdict.name())
                .query(Long.class).single();
    }

    private int ngonNgu(String code) {
        return jdbc.sql("SELECT id FROM languages WHERE code = :code")
                .param("code", code).query(Integer.class).single();
    }
}
