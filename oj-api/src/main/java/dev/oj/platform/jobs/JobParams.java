package dev.oj.platform.jobs;

/**
 * Khoá của {@code jobs.params} — một nguồn sự thật cho cả hai đầu và cho cả SQL.
 *
 * <h2>Vì sao hai chuỗi này đáng có một file riêng</h2>
 * Chúng xuất hiện ở <b>ba</b> nơi không kiểm tra chéo nhau được:
 *
 * <ol>
 *   <li>Nơi <i>tạo</i> job — {@code ImportTestdataUseCase}, {@code StartRejudgeUseCase},
 *       {@code RebuildStandingsJob}.</li>
 *   <li>Nơi <i>đọc</i> params — các {@code JobHandler} tương ứng.</li>
 *   <li><b>Trong một index của Postgres</b>: {@code ux_jobs_one_active_per_entity} (V9) khoá
 *       theo {@code COALESCE(params->>'problemId', params->>'contestId', '')}.</li>
 * </ol>
 *
 * <p>Chỗ thứ ba là chỗ nguy hiểm. Gõ sai khoá ở tầng Java thì index rơi về nhánh {@code ''},
 * nghĩa là <b>mọi</b> job cùng loại lại đụng nhau như trước V9 — và triệu chứng là "thỉnh
 * thoảng không nạp được testdata", không phải một ngoại lệ nào.
 *
 * <h2>Nó cũng là seam giữ luật ArchUnit 3</h2>
 * {@code problems} phải tạo được một job {@code REJUDGE} (FR-PROB-10) mà không import
 * {@code judging} — chiều đó không tồn tại. Với hằng số ở {@code platform}, việc đó thành
 * "điền một khoá vào một map", và không module nào phải biết module kia.
 */
public final class JobParams {

    /** {@code REJUDGE}, {@code TESTDATA_IMPORT}. Khớp nhánh đầu của index V9. */
    public static final String PROBLEM_ID = "problemId";

    /** {@code LEADERBOARD_REBUILD}, {@code STANDINGS_DRIFT_CHECK}. Khớp nhánh hai của index V9. */
    public static final String CONTEST_ID = "contestId";

    private JobParams() {
    }
}
