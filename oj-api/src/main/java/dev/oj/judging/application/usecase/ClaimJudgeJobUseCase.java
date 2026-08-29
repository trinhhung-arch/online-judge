package dev.oj.judging.application.usecase;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeJobDto;
import dev.oj.judging.application.port.JudgeQueueRepository;
import dev.oj.judging.application.port.JudgeQueueRepository.ClaimedJob;
import dev.oj.judging.application.port.SubmissionRepository;
import dev.oj.platform.config.AppProperties;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.trace.TraceIdFilter;
import dev.oj.problems.application.port.JudgeSpecRepository;
import dev.oj.problems.domain.JudgeSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Worker xin việc — {@code POST /internal/judge/claim}. Trả {@code 200} + job, hoặc
 * {@code 204} khi hàng đợi rỗng.
 *
 * <h2>Worker PULL, server không PUSH</h2>
 * Không có danh sách worker, không heartbeat, không service discovery. Bật thêm một worker là
 * nó tự vào việc, <b>không sửa một dòng config nào phía API</b> — đó là S2, và nó làm cho bài
 * test scalability tuần 12 (Mac + hai WSL) không tốn đồng nào.
 *
 * <h2>Hai lượt đọc, không phải ba</h2>
 * <pre>
 *   1. queue.claim(...)        UPDATE ... SKIP LOCKED RETURNING — kèm JOIN source_blobs
 *                              và languages, nên source và lệnh biên dịch về cùng một lượt
 *   2. judgeSpecs.findJudgeSpec giới hạn của đề + metadata từng test
 * </pre>
 * Cả hai chạy trên pool {@code judge} (6 connection), tách hẳn khỏi pool {@code app} — đó là
 * lý do 500 người nộp bài cùng lúc không làm worker đói connection
 * ({@code postgres-design.md} mục 11).
 *
 * <p>Job trả về mang giới hạn <b>đã quy về máy chấm chuẩn</b>: API nhân hệ số ngôn ngữ, worker
 * nhân {@code host_factor} của máy nó. Nhân cả hai ở một phía là bài Java được gấp đôi thời
 * gian mà không ai phát hiện ra.
 */
@Service
public class ClaimJudgeJobUseCase {

    private static final Logger log = LoggerFactory.getLogger(ClaimJudgeJobUseCase.class);

    private final JudgeQueueRepository queue;
    private final SubmissionRepository submissions;
    private final JudgeSpecRepository judgeSpecs;
    private final AppProperties.Judge config;

    public ClaimJudgeJobUseCase(JudgeQueueRepository queue,
                                SubmissionRepository submissions,
                                JudgeSpecRepository judgeSpecs,
                                AppProperties properties) {
        this.queue = queue;
        this.submissions = submissions;
        this.judgeSpecs = judgeSpecs;
        this.config = properties.judge();
    }

    /**
     * @return rỗng khi không có việc — controller trả {@code 204}, worker ngủ một nhịp
     */
    @JudgeTransactional
    public Optional<JudgeJobDto> claim(ClaimRequestDto request) {
        Optional<ClaimedJob> claimed = queue.claim(request.hostName(), config.leaseSeconds());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ClaimedJob job = claimed.get();

        // attempt đã tăng trong chính câu claim; ảnh chụp trên bảng nóng đi theo sau.
        submissions.markJudging(job.submissionId(), job.attempt());

        Optional<JudgeSpec> spec = judgeSpecs.findJudgeSpec(job.problemId(), job.testdataVersion());
        if (spec.isEmpty()) {
            return skipBrokenJob(job);
        }
        return Optional.of(buildJob(job, spec.get()));
    }

    /**
     * Đề mất testdata ở đúng phiên bản mà bài nộp đã đóng dấu — dữ liệu hỏng, không phải sự
     * cố nhất thời.
     *
     * <p><b>Cố ý KHÔNG ném ngoại lệ.</b> Ném là rollback, rollback là hàng quay lại hàng đợi
     * ngay lập tức, và worker sẽ claim lại chính nó trong vài mili giây — một vòng quay chiếm
     * trọn năng lực chấm của cả hệ thống vì <i>một</i> đề hỏng. Giữ nguyên claim thì bài đó
     * nằm im tới khi hết lease (120s), reaper trả nó về hàng đợi, và mọi bài khác vẫn được
     * chấm bình thường. Hỏng có kiểm soát, không hỏng dây chuyền.
     *
     * <p>Trả {@code 204} là một lời nói dối nhỏ với worker ("hết việc") — đổi lấy việc hệ
     * thống không đứng. Dòng ERROR này là thứ phải có người nhìn: M3 nên biến nó thành một
     * verdict {@code IE} thật qua đường {@code retryIe} để người nộp bài biết chuyện gì xảy ra,
     * thay vì thấy bài mình treo mãi ở {@code JUDGING}.
     */
    private Optional<JudgeJobDto> skipBrokenJob(ClaimedJob job) {
        log.error("Không dựng được job cho submission {}: đề {} không có testdata version {}. "
                        + "Bài giữ lease tới khi reaper thu hồi. SETTER cần kiểm tra lại đề này.",
                job.submissionId(), job.problemId(), job.testdataVersion());
        return Optional.empty();
    }

    /**
     * Ghép ba nguồn thành hợp đồng gửi cho worker: hàng đợi (bài + source), bảng
     * {@code languages} (lệnh + hệ số), và {@code problems} (giới hạn + danh sách test).
     *
     * <p>Dùng builder vì {@code JudgeJobDto} có năm {@code int} đứng liền nhau, và đảo nhầm
     * hai cái thì trình biên dịch im lặng còn hệ thống chấm sai giới hạn.
     */
    /**
     * Tên file mã nguồn worker sẽ đặt trong box: {@code Main.} + {@code source_extension}.
     *
     * <p>Tính ở đây vì chỉ API mới có bảng {@code languages}. Worker <b>không được</b> tự suy
     * ra từ {@code languageCode}: làm thế là dựng một bảng tra thứ hai cho cùng một dữ kiện,
     * và ngày hai bảng lệch nhau thì mọi bài của ngôn ngữ đó {@code CE} với thông báo vô nghĩa.
     *
     * <p>Phần <b>tên</b> là {@code Main} chứ không phải một tên tuỳ ý, và đó không phải sở
     * thích: {@code languages.run_command} của Java viết {@code -cp {dir} Main}, nên lớp phải
     * tên {@code Main}, nên file phải là {@code Main.java}. Một tên khác thì bài Java nào cũng
     * {@code CE} — và {@code CE} vì tên file là loại lỗi thí sinh không thể tự hiểu.
     */
    static final String SOURCE_BASE_NAME = "Main";

    private static String sourceFileName(ClaimedJob.LanguageSpec language) {
        return SOURCE_BASE_NAME + '.' + language.sourceExtension();
    }

    private JudgeJobDto buildJob(ClaimedJob job, JudgeSpec spec) {
        ClaimedJob.LanguageSpec language = job.language();
        return JudgeJobDto.builder()
                .submission(job.submissionId(), job.attempt())
                .traceId(TraceIdFilter.current())
                .language(language.code(), language.compileCommand(), language.runCommand())
                .compileLimits(language.compileTimeLimitMs(), language.compileMemoryKb())
                .runLimitsOnReferenceHost(
                        spec.timeLimitOnReferenceHost(
                                language.timeMultiplier(), language.startupOverheadMs()),
                        spec.memoryLimitOnReferenceHost(language.memoryOverheadKb()),
                        spec.outputLimitKb())
                .source(sourceFileName(language), job.sourceContent(), job.sourceSha256())
                .checker(spec.checkerType(), spec.checkerEpsilon())
                .scoring(spec.scoringMode(), spec.maxScore())
                .testdata(spec.testdataVersion(), spec.manifestSha256(), spec.testcases())
                // FR-PROB-06 — rỗng với đề không chia nhóm; hợp đồng kiểm rằng nó có nội
                // dung khi và chỉ khi scoringMode=SUBTASK, nên quên dòng này thì hỏng NGAY
                // ở đây chứ không hỏng ở worker sau khi đã chấm xong.
                .subtasks(spec.subtasks())
                .build();
    }
}
