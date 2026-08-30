package dev.oj.judging.application.usecase;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ScoringMode;
import dev.oj.judging.domain.DomainRules;
import dev.oj.judging.domain.JudgingException;
import dev.oj.judging.domain.SubmissionStatus;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.application.port.ProblemRepository;
import dev.oj.problems.domain.FeedbackLevel;
import dev.oj.problems.domain.Problem;
import dev.oj.problems.domain.ProblemNotFoundException;
import dev.oj.problems.domain.ProblemStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * ★ Đường nóng, ngân sách 300ms. Test này canh <b>hình dạng</b> của use-case, không phải kết
 * quả của nó: {@code accept != process} là một tính chất về thứ tự lời gọi, và một assertion
 * trên giá trị trả về không nhìn thấy được nó.
 */
class SubmitSolutionUseCaseTest {

    private JudgingFakes fakes;
    private SubmitSolutionUseCase useCase;
    private JudgingFakes.RateLimiterGia rateLimiter;
    private LichThiGia lichThi;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        rateLimiter = new JudgingFakes.RateLimiterGia();
        lichThi = new LichThiGia();
        useCase = new SubmitSolutionUseCase(
                JudgingFakes.userIs(7L, Role.USER),
                new GetProblemUseCase(problemRepositoryReturning(publishedProblem()), statements(),
                        lichThi, JudgingFakes.userIs(7L, Role.USER)),
                fakes.languages, fakes.sourceBlobs, fakes.submissions, rateLimiter,
                lichThi, fakes.queue, fakes.publisher, fakes.txManager);
    }

    private SubmitSolutionUseCase.Command command() {
        return new SubmitSolutionUseCase.Command(42L, "cpp20", JudgingFakes.SOURCE);
    }

    /**
     * ★ Bất biến #2 diễn đạt thành một danh sách: ba câu ghi nằm <b>trong</b> transaction,
     * publish nằm <b>sau</b> COMMIT, và không có gì khác chen vào giữa.
     */
    @Test
    @DisplayName("★ M5 · bài nộp ngoài kỳ thi có contest_id = null")
    void ngoai_ky_thi_thi_contest_id_null() {
        useCase.submit(command());

        assertThat(fakes.submissions.inserted.contestId()).isNull();
    }

    @Test
    @DisplayName("★ M5 · contest_id SUY RA từ máy chủ, client không khai được")
    void contest_id_suy_ra_tu_may_chu() {
        lichThi.contestDangChay = 77L;

        useCase.submit(command());

        // Command chỉ có problemId, languageCode, source — không có chỗ nào để client khai
        // contest. Nếu ngày nào đó ai thêm một trường contestId vào Command, ca này vẫn xanh
        // nhưng ca dưới sẽ đỏ.
        assertThat(fakes.submissions.inserted.contestId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("★ M5 · Command KHÔNG có trường contest nào — client không được chọn")
    void command_khong_co_truong_contest() {
        var truong = java.util.Arrays.stream(SubmitSolutionUseCase.Command.class
                        .getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .map(t -> t.toLowerCase(java.util.Locale.ROOT))
                .toList();

        // Client khai được contest nghĩa là khai được contest KHÁC, hoặc khai không có contest
        // nào để bài của mình không vào bảng xếp hạng — nộp thử trong giờ thi mà không bị tính
        // penalty. Cả hai đều phá đúng thứ hệ thống này bán.
        assertThat(truong).noneMatch(t -> t.contains("contest"));
    }

    @Test
    @DisplayName("★ FR-SUB-08 · chốt rate limit chạy TRƯỚC persist — bị chặn thì không ghi gì")
    void rate_limit_chan_truoc_khi_ghi() {
        rateLimiter.chan = true;

        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> useCase.submit(command()))
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo(DomainException.Kind.RATE_LIMITED);
                    // retryAfter đi ra header Retry-After — FR-SUB-08 là quy tắc được CÔNG BỐ,
                    // UI phải hiện được đếm ngược thay vì để người dùng đoán.
                    assertThat(e.retryAfter()).isNotNull();
                });

        assertThat(fakes.submissions.inserted).isNull();
        assertThat(fakes.queue.enqueuedPriority).isNull();
        assertThat(fakes.publisher.published).isNull();
    }

    @Test
    @DisplayName("★ chốt rate limit chạy SAU validate — request sai không tiêu mất 10 giây")
    void rate_limit_khong_bi_tinh_khi_dau_vao_sai() {
        var quaDai = new SubmitSolutionUseCase.Command(
                42L, "cpp20", "a".repeat(DomainRules.MAX_SOURCE_BYTES + 1));

        assertThatExceptionOfType(JudgingException.class).isThrownBy(() -> useCase.submit(quaDai));

        assertThat(rateLimiter.soLanGoi)
                .describedAs("dán nhầm một file quá lớn mà bị khoá 10 giây là phạt nhầm lỗi")
                .isZero();
    }

    @Test
    @DisplayName("publish nằm SAU commit, và ba câu ghi nằm TRONG transaction")
    void thu_tu_loi_goi_dung_nhu_dac_ta() {
        useCase.submit(command());

        assertThat(fakes.calls).containsExactly(
                "languages.findEnabledByCode",
                "tx.begin",
                "sourceBlobs.saveIfAbsent",
                "submissions.insert",
                "queue.enqueue",
                "tx.commit",
                "events.publishEnqueued");
    }

    /**
     * ★ Publish hỏng <b>không</b> làm hỏng bài nộp: hàng đã nằm trong {@code judge_queue} và
     * reaper sẽ nhặt. Đây chính là lý do reaper tồn tại — bỏ dòng try/catch trong use-case
     * thì test này đỏ, và người dùng nhận 500 cho một bài đã được ghi thành công.
     */
    @Test
    void publisher_nem_loi_thi_van_nhan_bai() {
        fakes.publisher.explode = true;

        var accepted = useCase.submit(command());

        assertThat(accepted.submissionId()).isEqualTo(101L);
        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);
        assertThat(fakes.calls).contains("tx.commit", "events.publishEnqueued");
    }

    @Test
    void tra_ve_QUEUED_chu_khong_bao_gio_tra_ve_verdict() {
        var accepted = useCase.submit(command());

        assertThat(accepted.status()).isEqualTo(SubmissionStatus.QUEUED);
        // Không có lời gọi nào tới worker, tới hàng đợi kết quả, hay tới bất cứ thứ gì chờ đợi.
        assertThat(fakes.calls).noneMatch(c -> c.contains("judgeRuns") || c.contains("markDone")
                || c.contains("claim"));
    }

    @Test
    void bai_vao_hang_doi_o_muc_uu_tien_live() {
        useCase.submit(command());

        assertThat(fakes.queue.enqueuedPriority).isEqualTo(DomainRules.PRIORITY_LIVE);
    }

    /** Bài nộp đóng dấu phiên bản testdata tại lúc nộp — FR-PROB-10 sống nhờ con số này. */
    @Test
    void dong_dau_testdata_version_va_khoa_source() {
        useCase.submit(command());

        assertThat(fakes.submissions.inserted.testdataVersion()).isEqualTo(5);
        assertThat(fakes.submissions.inserted.sourceSha256()).isEqualTo(JudgingFakes.SHA);
        assertThat(fakes.submissions.inserted.userId()).isEqualTo(7L);
        assertThat(fakes.sourceBlobs.saved.sha256()).isEqualTo(JudgingFakes.SHA);
    }

    @Test
    @DisplayName("hỏng ở khâu validate thì KHÔNG có câu ghi nào chạy")
    void validate_hong_thi_khong_ghi_gi() {
        fakes.languages.enabled = null;

        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> useCase.submit(command()))
                .satisfies(e -> assertThat(e.code()).isEqualTo("submission.language_not_available"));

        assertThat(fakes.calls).doesNotContain("tx.begin", "submissions.insert");
    }

    @Test
    void source_qua_64KB_bi_tu_choi_truoc_moi_cau_ghi() {
        var qua_dai = new SubmitSolutionUseCase.Command(
                42L, "cpp20", "a".repeat(DomainRules.MAX_SOURCE_BYTES + 1));

        assertThatExceptionOfType(JudgingException.class)
                .isThrownBy(() -> useCase.submit(qua_dai))
                .satisfies(e -> assertThat(e.code()).isEqualTo("submission.source_too_large"));

        assertThat(fakes.calls).isEmpty();
    }

    @Test
    void de_khong_ton_tai_thi_khong_ghi_gi() {
        var useCaseNoProblem = new SubmitSolutionUseCase(
                JudgingFakes.userIs(7L, Role.USER),
                new GetProblemUseCase(problemRepositoryReturning(null), statements(),
                        lichThi, JudgingFakes.userIs(7L, Role.USER)),
                fakes.languages, fakes.sourceBlobs, fakes.submissions, rateLimiter,
                lichThi, fakes.queue, fakes.publisher, fakes.txManager);

        assertThatExceptionOfType(ProblemNotFoundException.class)
                .isThrownBy(() -> useCaseNoProblem.submit(command()));
        assertThat(fakes.calls).doesNotContain("tx.begin");
    }

    /** Command không được để mã nguồn lọt vào một dòng log (bất biến #9). */
    @Test
    void toString_cua_command_khong_chua_ma_nguon() {
        assertThat(command().toString()).doesNotContain("main", "return");
    }

    private static Problem publishedProblem() {
        return new Problem(42L, "A-PLUS-B", "A + B", "Cộng hai số.",
                1000, 262_144, 65_536, CheckerType.TOKEN, null, ScoringMode.ALL_OR_NOTHING,
                FeedbackLevel.TEST_INDEX, ProblemStatus.PUBLISHED, 5, 1L, false);
    }

    private static ProblemRepository problemRepositoryReturning(Problem problem) {
        return new ProblemRepository() {
            @Override
            public Optional<Problem> findPublishedByCode(String code) {
                return Optional.ofNullable(problem);
            }

            @Override
            public Optional<Problem> findPublishedById(long id) {
                return Optional.ofNullable(problem);
            }

            @Override
            public dev.oj.platform.web.CursorPage<ProblemListItem> danhSachDaXuatBan(
                    ListFilter loc, Long cursor, int size) {
                return new dev.oj.platform.web.CursorPage<>(java.util.List.of(), null);
            }
        };
    }

    /**
     * {@code StatementService} thật nhưng với một cache rỗng — đường nộp bài không render đề,
     * nên nó chỉ cần tồn tại để {@code GetProblemUseCase} dựng được.
     */
    private static dev.oj.problems.application.StatementService statements() {
        var renderer = new dev.oj.problems.application.port.StatementRenderer() {
            @Override
            public String render(String markdown) {
                return markdown;
            }

            @Override
            public String version() {
                return "test";
            }
        };
        var cache = new dev.oj.problems.application.port.RenderedStatementRepository() {
            @Override
            public Optional<String> tim(String hash, String version) {
                return Optional.empty();
            }

            @Override
            public void luu(String hash, String version, String html) {
            }
        };
        return new dev.oj.problems.application.StatementService(renderer, cache);
    }
}
