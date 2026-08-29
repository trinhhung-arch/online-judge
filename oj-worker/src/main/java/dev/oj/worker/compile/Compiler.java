package dev.oj.worker.compile;

import dev.oj.contract.JudgeJobDto;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.sandbox.CommandTemplate;
import dev.oj.worker.sandbox.IsolateBox;
import dev.oj.worker.sandbox.IsolateCommand;
import dev.oj.worker.sandbox.IsolateMeta;
import dev.oj.worker.sandbox.SandboxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Biên dịch — <b>bên trong box</b>. Bước 2.4 của {@code build-order.md}, bất biến #4.
 *
 * <h2>Compiler bomb là có thật, và đây là số đo</h2>
 * File {@code 14-compiler-bomb.cpp} trong bộ test tấn công dài 20 dòng. Chạy trong box với
 * trần 512MB: {@code cc1plus} bị cgroup giết sau <b>4,4 giây</b>. Chạy ngoài box thì 4,4 giây
 * đó là RAM của host, và nó xảy ra <b>trước khi</b> có bất kỳ dòng mã người dùng nào được
 * thực thi — tức là "chỉ biên dịch thôi, có chạy gì đâu" là một câu sai.
 *
 * <h2>Vượt giờ hoặc vượt RAM lúc biên dịch là {@code CE}, không phải {@code IE}</h2>
 * Máy chấm vẫn hoạt động đúng: nó đã từ chối một chương trình không biên dịch nổi trong hạn
 * mức. {@code IE} sẽ khiến API cho chấm lại 2 lần nữa (FR-SUB-12) — ba lần đốt 512MB và 10
 * giây CPU cho cùng một quả bom. {@code CE} kèm câu giải thích rõ là câu trả lời đúng, và nó
 * cũng nói thật với thí sinh.
 *
 * <h2>Ngôn ngữ thông dịch không có bước này</h2>
 * {@code py311} không có {@code compile_command}, nên "artifact" chính là file mã nguồn.
 * Nhờ đó phần còn lại của đường chấm chỉ biết một khái niệm duy
 * nhất — một file đặt vào box rồi chạy — thay vì hai nhánh {@code if} lặp ở mọi tầng.
 */
@Component
public class Compiler {

    private static final Logger log = LoggerFactory.getLogger(Compiler.class);

    private final WorkerProperties properties;
    private final CompileCache cache;

    public Compiler(WorkerProperties properties) {
        this.properties = properties;
        this.cache = new CompileCache(
                properties.sandbox().cache().dir().resolve("binaries"),
                properties.sandbox().cache().maxEntries());
    }

    /**
     * Thứ được đặt vào box trước mỗi lượt chạy: binary đã biên dịch, hoặc chính file mã nguồn
     * với ngôn ngữ thông dịch.
     */
    public record Artifact(String name, byte[] content) {
    }

    /** {@code artifact == null} nghĩa là biên dịch hỏng, và {@code compileLog} nói vì sao. */
    public record CompileResult(Artifact artifact, String compileLog) {

        public boolean failed() {
            return artifact == null;
        }
    }

    /**
     * @param box            box đã {@code init}; phương thức này {@link IsolateBox#reset()}
     *                       trước khi dùng để bộ đếm bộ nhớ sạch
     * @param sourceFileName tên file mã nguồn trong box — {@code languages.source_extension}
     *                       quyết định phần mở rộng, và {@code g++} cần nó để biết đây là C++
     */
    public CompileResult compile(IsolateBox box, JudgeJobDto job, String sourceFileName) {
        if (job.isInterpreted()) {
            return new CompileResult(
                    new Artifact(sourceFileName,
                            job.sourceContent().getBytes(StandardCharsets.UTF_8)),
                    null);
        }

        String key = job.compileCacheKey();
        var cached = cache.find(key);
        if (cached.isPresent()) {
            log.debug("cache hit biên dịch cho submission {}", job.submissionId());
            return new CompileResult(
                    new Artifact(CommandTemplate.BINARY_NAME, cached.get()), null);
        }

        box.reset();
        box.write(sourceFileName, job.sourceContent());

        List<String> argv = IsolateCommand.compile(
                properties.sandbox(), box.boxId(), box.metaFile(),
                job.compileTimeLimitMs(), job.compileMemoryKb(),
                CommandTemplate.expand(job.compileCommand(), sourceFileName, job.memoryLimitKb(),
                        properties.sandbox().programPath()));

        long logLimit = properties.sandbox().compile().logLimit().toBytes();
        IsolateBox.Execution execution = box.execute(
                argv, null, logLimit, logLimit, watchdog(job.compileTimeLimitMs()));

        String compileLog = sanitize(execution.stderr().text() + execution.stdout().text());
        IsolateMeta.Outcome outcome = execution.meta().outcome();

        if (outcome == IsolateMeta.Outcome.INTERNAL_ERROR) {
            // Máy chấm hỏng, không phải bài nộp hỏng. IE để API cho chấm lại.
            throw new SandboxException("isolate hỏng khi biên dịch submission "
                    + job.submissionId() + ": " + execution.meta().diagnostic());
        }
        if (outcome == IsolateMeta.Outcome.TIME_LIMIT) {
            return new CompileResult(null, compileLog + explainKill(
                    "Biên dịch vượt " + job.compileTimeLimitMs() + "ms."));
        }
        if (outcome == IsolateMeta.Outcome.MEMORY_LIMIT) {
            return new CompileResult(null, compileLog + explainKill(
                    "Trình biên dịch vượt " + job.compileMemoryKb() + "KB bộ nhớ."));
        }
        if (outcome == IsolateMeta.Outcome.RUNTIME_ERROR) {
            return new CompileResult(null, compileLog);
        }
        if (!box.hasRegularFile(CommandTemplate.BINARY_NAME)) {
            return new CompileResult(null, compileLog + explainKill(
                    "Lệnh biên dịch báo thành công nhưng không tạo ra "
                            + CommandTemplate.BINARY_NAME + "."));
        }

        byte[] binary = box.takeRegularFile(CommandTemplate.BINARY_NAME);
        cache.store(key, binary);
        return new CompileResult(new Artifact(CommandTemplate.BINARY_NAME, binary), compileLog);
    }

    private Duration watchdog(int compileTimeLimitMs) {
        return Duration.ofMillis(2L * compileTimeLimitMs)
                .plus(properties.sandbox().watchdogSlack());
    }

    private static String explainKill(String reason) {
        return "\n=== " + reason + " Thường là do #include lồng nhau quá sâu hoặc template "
                + "sinh ra quá nhiều kiểu. ===\n";
    }

    /**
     * Bỏ tiền tố {@code /box/} khỏi thông báo của compiler.
     *
     * <p>Log compiler <b>được phép</b> cho tác giả bài nộp xem ({@code oj-api/CLAUDE.md} mục 2),
     * nhưng đường dẫn tuyệt đối bên trong box thì không nằm trong thứ họ cần
     * ({@code oj-worker/CLAUDE.md} mục 7). Bỏ đi thì thông báo còn đúng nguyên và đọc dễ hơn:
     * {@code Main.cpp:3:5: error} thay vì {@code /box/Main.cpp:3:5: error}.
     */
    private static String sanitize(String compilerOutput) {
        return compilerOutput.replace(IsolateCommand.BOX_DIR + "/", "");
    }
}
