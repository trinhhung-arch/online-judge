package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.contract.Verdict;
import dev.oj.worker.compile.Compiler;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.run.checker.Checker;
import dev.oj.worker.sandbox.CommandTemplate;
import dev.oj.worker.sandbox.IsolateBox;
import dev.oj.worker.sandbox.IsolateCommand;
import dev.oj.worker.sandbox.IsolateMeta;
import dev.oj.worker.sandbox.SandboxException;
import dev.oj.worker.testdata.TestdataFetcher;
import dev.oj.worker.testdata.TestdataUnavailableException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Chạy đúng một testcase. Bước 2.7 của {@code build-order.md}.
 *
 * <h2>★ Input đi vào bằng stdin, và testdata KHÔNG BAO GIỜ vào box</h2>
 * {@link TestdataFetcher} trả về một đường dẫn <b>trên host</b>;
 * {@code ProcessBuilder.redirectInput} mở nó thành một file descriptor mà tiến trình con thừa
 * hưởng. Chương trình đọc được nội dung nhưng <b>không thấy file</b>.
 *
 * <p>Đây là bất biến #1 ở dạng cụ thể nhất của nó. Nếu ai đó "đơn giản hoá" thành copy file
 * input vào box rồi {@code --stdin=input.txt}, thì một chương trình bốn dòng
 * {@code opendir("/box")} lấy được toàn bộ bộ test — nộp một bảng tra cứu là AC mọi bài, và
 * không có dòng log nào cho thấy chuyện đó đã xảy ra (SEC3, {@code frplan.md} 3.1).
 * Test tấn công 10 tồn tại đúng để bắt ngày ai đó làm việc ấy.
 *
 * <h2>{@link IsolateBox#reset()} trước mỗi test, không phải mỗi bài</h2>
 * {@code cg-mem} là đỉnh bộ nhớ tính từ lúc box sinh ra. Không reset thì test 5 báo bộ nhớ
 * của test 1..5 gộp lại — một bài dùng 200MB ở test 1 rồi 10MB ở test 2 sẽ bị MLE ở test 2.
 * Giá của reset đo được là ~5ms một lượt.
 */
@Component
public class TestRunner {

    private final WorkerProperties properties;
    private final TestdataFetcher testdata;

    public TestRunner(WorkerProperties properties, TestdataFetcher testdata) {
        this.properties = properties;
        this.testdata = testdata;
    }

    /**
     * @param verdict    {@code AC} · {@code WA} · {@code TLE} · {@code MLE} · {@code RE}
     * @param diagnostic chuỗi cho {@code judge_runs.isolate_status} — <b>chỉ ADMIN xem</b>,
     *                   không bao giờ chứa output của chương trình hay nội dung testcase
     */
    public record TestOutcome(Verdict verdict, long cpuTimeMs, long memoryKb, String diagnostic) {

        public boolean accepted() {
            return verdict == Verdict.AC;
        }
    }

    public TestOutcome run(IsolateBox box, JudgeJobDto job, Compiler.Artifact artifact,
                           TestcaseMetaDto testcase, BigDecimal hostFactor, Checker checker) {
        Path input = testdata.fetch(testcase.inputSha256());
        byte[] expected = read(testdata.fetch(testcase.outputSha256()), testcase.outputSha256());

        box.reset();
        box.writeExecutable(artifact.name(), artifact.content());

        long cpuLimitMs = job.effectiveCpuLimitMs(hostFactor);
        long wallLimitMs = job.effectiveWallLimitMs(hostFactor);
        List<String> argv = IsolateCommand.run(
                properties.sandbox(), box.boxId(), box.metaFile(),
                cpuLimitMs, wallLimitMs, job.memoryLimitKb(),
                CommandTemplate.expand(job.runCommand(), artifact.name(), job.memoryLimitKb(),
                        properties.sandbox().programPath()));

        IsolateBox.Execution execution = box.execute(
                argv, input,
                (long) job.outputLimitKb() * 1024,
                properties.sandbox().run().stderrLimit().toBytes(),
                Duration.ofMillis(wallLimitMs).plus(properties.sandbox().watchdogSlack()));

        IsolateMeta meta = execution.meta();
        return new TestOutcome(verdictOf(meta, execution.stdout(), expected, checker),
                meta.cpuTimeMs(), meta.memoryKb(), meta.diagnostic());
    }

    /**
     * <h2>Thứ tự các nhánh ở đây là một quyết định, không phải ngẫu nhiên</h2>
     * Chương trình bị giết vì hết giờ <b>trong lúc</b> đang tuôn output thì lý do thật là hết
     * giờ, nên {@code TIME_LIMIT} phải được xét trước cờ {@code truncated}.
     *
     * <h2>Vì sao output vượt trần là {@code WA} chứ không phải {@code RE}</h2>
     * Vì chương trình không hề bị lỗi: nó chạy xong bình thường và in ra quá nhiều. Báo
     * {@code RE} là nói với thí sinh rằng chương trình của họ đổ vỡ, và họ sẽ đi tìm một lỗi
     * không tồn tại. Output dài hơn trần thì <b>chắc chắn</b> không thể khớp đáp án, nên
     * {@code WA} vừa đúng vừa dẫn họ tới chỗ cần sửa. Bộ verdict của dự án không có
     * {@code OLE} ({@code oj-contract} {@code Verdict}), và thêm một verdict là đổi hợp đồng.
     */
    private Verdict verdictOf(IsolateMeta meta, OutputLimiter.Captured stdout,
                              byte[] expected, Checker checker) {
        return switch (meta.outcome()) {
            case TIME_LIMIT -> Verdict.TLE;
            case MEMORY_LIMIT -> Verdict.MLE;
            case RUNTIME_ERROR -> Verdict.RE;
            case INTERNAL_ERROR -> throw new SandboxException(
                    "isolate trả mã lạ khi chạy: " + meta.diagnostic());
            case OK -> stdout.truncated() || !checker.matches(expected, stdout.bytes())
                    ? Verdict.WA
                    : Verdict.AC;
        };
    }

    private static byte[] read(Path file, String sha256) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new TestdataUnavailableException("Không đọc được đáp án " + sha256, e);
        }
    }
}
