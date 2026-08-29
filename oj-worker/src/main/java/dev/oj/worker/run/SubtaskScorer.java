package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.SubtaskResultDto;
import dev.oj.contract.SubtaskScoring;
import dev.oj.contract.SubtaskSpecDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★ Bước 3.3 · FR-PROB-06 — chấm điểm theo nhóm test.
 *
 * <h2>Vì sao class này ĐIỀU KHIỂN vòng chạy chứ không tính điểm sau khi chạy xong</h2>
 * Vì phụ thuộc giữa nhóm là một quyết định <b>chạy hay không chạy</b>, không phải một phép
 * cộng. Nhóm 3 khai báo phụ thuộc nhóm 1 và 2 nghĩa là: nhóm 1 hoặc 2 không trọn điểm thì
 * nhóm 3 <b>không được chấm</b> — và đó chính là ích lợi của tính năng. Một đề IOI điển hình
 * có nhóm cuối với dữ liệu lớn nhất; chạy nó cho một bài đã hỏng ở nhóm 1 là đốt vài chục
 * giây của máy chấm để nhận về một con số đã biết trước là 0.
 *
 * <p>Tính điểm sau thì đúng số, nhưng đã trả xong toàn bộ cái giá mà tính năng sinh ra để
 * tránh.
 *
 * <h2>Ba luật, và luật thứ ba là luật dễ quên</h2>
 * <ol>
 *   <li>{@code MIN} — sai một test là cả nhóm 0 điểm. Nên <b>dừng nhóm ngay ở test sai đầu
 *       tiên</b>: những test còn lại không đổi được điểm nữa.</li>
 *   <li>{@code SUM} — mỗi test đạt góp {@code points / số test của nhóm}. Phải chạy hết,
 *       không có early exit.</li>
 *   <li><b>Nhóm bị bỏ qua KHÁC nhóm 0 điểm.</b> Cả hai đều cho 0, nhưng "bỏ qua" nói với thí
 *       sinh rằng nhóm ấy <i>chưa được thử</i> — sửa nhóm 1 thì nhóm 3 có thể ăn điểm.
 *       {@link SubtaskResultDto#skipped} mang {@code verdict = null} đúng để phân biệt
 *       chuyện đó, và trang kết quả phải hiện hai thứ này khác nhau.</li>
 * </ol>
 *
 * <h2>Không cần sắp xếp tô-pô</h2>
 * {@code SubtaskSpecDto} chỉ cho phụ thuộc vào nhóm có số thứ tự <b>nhỏ hơn</b>, nên duyệt
 * một lượt theo thứ tự tăng dần là mọi phụ thuộc đã có kết quả. Không có chu trình để phát
 * hiện, không có nhánh nào để sai.
 */
public final class SubtaskScorer {

    private SubtaskScorer() {
    }

    /** Chạy đúng một test. {@code JobExecutor} cung cấp; class này quyết định gọi hay không. */
    @FunctionalInterface
    public interface TestExecutor {
        TestRunner.TestOutcome run(TestcaseMetaDto testcase);
    }

    /**
     * @param verdict       verdict tổng của bài — {@code AC} chỉ khi trọn điểm
     * @param failedOrdinal test sai <b>đầu tiên</b> theo thứ tự chạy, hoặc {@code null}
     * @param testsRun      số test thật sự đã chạy; nhóm bị bỏ qua không tính vào đây
     */
    public record Result(
            int score,
            Verdict verdict,
            Integer failedOrdinal,
            int testsRun,
            long maxCpuTimeMs,
            long maxMemoryKb,
            List<SubtaskResultDto> subtasks) {
    }

    public static Result score(JudgeJobDto job, TestExecutor executor) {
        Map<Integer, List<TestcaseMetaDto>> byGroup = groupTests(job.testcases());
        Map<Integer, SubtaskResultDto> done = new LinkedHashMap<>();

        int totalScore = 0;
        int testsRun = 0;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        Verdict overall = Verdict.AC;
        Integer failedOrdinal = null;

        for (SubtaskSpecDto subtask : job.subtasks()) {
            if (!dependenciesSatisfied(subtask, done)) {
                done.put(subtask.ordinal(),
                        SubtaskResultDto.skipped(subtask.ordinal(), subtask.points()));
                continue;
            }

            List<TestcaseMetaDto> tests = byGroup.getOrDefault(subtask.ordinal(), List.of());
            GroupRun run = runGroup(subtask, tests, executor);

            testsRun += run.testsRun;
            maxCpuMs = Math.max(maxCpuMs, run.maxCpuMs);
            maxMemoryKb = Math.max(maxMemoryKb, run.maxMemoryKb);
            totalScore += run.result.score();
            done.put(subtask.ordinal(), run.result);

            if (run.result.verdict() != Verdict.AC && failedOrdinal == null) {
                overall = run.result.verdict();
                failedOrdinal = run.result.failedTestOrdinal();
            }
        }

        // Verdict tổng là AC KHI VÀ CHỈ KHI trọn điểm. Một bài ăn 90/100 mà hiện "AC" thì
        // bảng xếp hạng nói một đằng, trang bài nộp nói một nẻo.
        if (totalScore < job.maxScore() && overall == Verdict.AC) {
            overall = Verdict.WA;
        }
        return new Result(totalScore, overall, failedOrdinal, testsRun,
                maxCpuMs, maxMemoryKb, List.copyOf(done.values()));
    }

    /** Phụ thuộc chỉ thoả khi nhóm được phụ thuộc ăn TRỌN điểm, không phải "có điểm". */
    private static boolean dependenciesSatisfied(SubtaskSpecDto subtask,
                                                 Map<Integer, SubtaskResultDto> done) {
        for (Integer dependency : subtask.dependsOn()) {
            SubtaskResultDto result = done.get(dependency);
            if (result == null || result.isSkipped() || result.score() < result.maxScore()) {
                return false;
            }
        }
        return true;
    }

    private record GroupRun(SubtaskResultDto result, int testsRun, long maxCpuMs, long maxMemoryKb) {
    }

    private static GroupRun runGroup(SubtaskSpecDto subtask, List<TestcaseMetaDto> tests,
                                     TestExecutor executor) {
        int accepted = 0;
        int testsRun = 0;
        long maxCpuMs = 0;
        long maxMemoryKb = 0;
        Verdict groupVerdict = Verdict.AC;
        Integer failedOrdinal = null;

        for (TestcaseMetaDto testcase : tests) {
            TestRunner.TestOutcome outcome = executor.run(testcase);
            testsRun++;
            maxCpuMs = Math.max(maxCpuMs, outcome.cpuTimeMs());
            maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());

            if (outcome.accepted()) {
                accepted++;
                continue;
            }
            if (failedOrdinal == null) {
                groupVerdict = outcome.verdict();
                failedOrdinal = testcase.ordinal();
            }
            // Luật 1: với MIN thì nhóm đã 0 điểm, mọi test còn lại không đổi được gì nữa.
            if (subtask.scoring() == SubtaskScoring.MIN) {
                break;
            }
        }

        int score = scoreOf(subtask, accepted, tests.size());
        return new GroupRun(
                new SubtaskResultDto(subtask.ordinal(), groupVerdict, score, subtask.points(),
                        failedOrdinal, (int) maxCpuMs, (int) maxMemoryKb),
                testsRun, maxCpuMs, maxMemoryKb);
    }

    /**
     * {@code SUM} chia đều điểm cho các test và <b>làm tròn xuống</b> — tổng có thể nhỏ hơn
     * {@code points} vài đơn vị khi số test không chia hết.
     *
     * <p>Chấp nhận việc đó thay vì phân phối phần dư: mọi cách chia phần dư đều thiên vị một
     * số test cụ thể, và thí sinh sẽ phát hiện ra rằng "đạt test 1 đáng giá hơn test 2".
     * Đạt <b>hết</b> thì được trọn điểm — nhánh riêng ở dưới bảo đảm điều đó, và đó là
     * trường hợp duy nhất con số cuối cùng thật sự quan trọng.
     */
    private static int scoreOf(SubtaskSpecDto subtask, int accepted, int testCount) {
        if (testCount == 0) {
            return 0;
        }
        if (accepted == testCount) {
            return subtask.points();
        }
        return subtask.scoring() == SubtaskScoring.MIN
                ? 0
                : subtask.points() * accepted / testCount;
    }

    private static Map<Integer, List<TestcaseMetaDto>> groupTests(List<TestcaseMetaDto> testcases) {
        Map<Integer, List<TestcaseMetaDto>> byGroup = new LinkedHashMap<>();
        for (TestcaseMetaDto testcase : testcases) {
            byGroup.computeIfAbsent(testcase.subtaskOrdinal(), key -> new ArrayList<>())
                    .add(testcase);
        }
        return byGroup;
    }
}
