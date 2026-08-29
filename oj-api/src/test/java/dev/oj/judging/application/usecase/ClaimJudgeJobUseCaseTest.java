package dev.oj.judging.application.usecase;

import dev.oj.contract.CheckerType;
import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.ScoringMode;
import dev.oj.contract.TestcaseMetaDto;
import dev.oj.judging.application.port.JudgeQueueRepository.ClaimedJob;
import dev.oj.problems.application.port.JudgeSpecRepository;
import dev.oj.problems.domain.JudgeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Worker xin việc. Kiểm hai thứ: giới hạn được quy đổi đúng, và job hỏng không làm đứng hàng đợi. */
class ClaimJudgeJobUseCaseTest {

    private static final ClaimRequestDto REQUEST = ClaimRequestDto.single("mac-m1max-host", "arm64");

    private JudgingFakes fakes;
    private JudgeSpec spec;
    private ClaimJudgeJobUseCase useCase;

    @BeforeEach
    void setUp() {
        fakes = new JudgingFakes();
        spec = new JudgeSpec(42L, 1000, 262_144, 65_536, CheckerType.TOKEN, null,
                ScoringMode.ALL_OR_NOTHING, 5, JudgingFakes.SHA, List.of(),
                List.of(new TestcaseMetaDto(1, true, JudgingFakes.SHA, JudgingFakes.SHA, null)));
        useCase = new ClaimJudgeJobUseCase(fakes.queue, fakes.submissions,
                specRepositoryReturning(spec), JudgingFakes.properties());
    }

    private static ClaimedJob claimedJob() {
        return new ClaimedJob(101L, 1, 42L, 5, JudgingFakes.SHA, JudgingFakes.SOURCE,
                new ClaimedJob.LanguageSpec("java21", "java", "javac Main.java", "java Main",
                        10_000, 1_048_576, new BigDecimal("2.00"), 100, 131_072));
    }

    @Test
    void hang_doi_rong_thi_tra_204_va_khong_danh_dau_gi() {
        assertThat(useCase.claim(REQUEST)).isEmpty();
        assertThat(fakes.calls).containsExactly("queue.claim");
    }

    /**
     * API nhân hệ số ngôn ngữ và cộng phần hao khởi động; worker sẽ nhân tiếp
     * {@code host_factor}. Nhân cả hai ở một phía là bài Java được gấp đôi thời gian mà không
     * ai phát hiện ra ({@code nfrplan.md} 9.1).
     */
    @Test
    void gioi_han_duoc_quy_ve_may_cham_chuan_theo_he_so_ngon_ngu() {
        fakes.queue.nextClaim = claimedJob();

        var job = useCase.claim(REQUEST).orElseThrow();

        assertThat(job.timeLimitMs()).isEqualTo(1000 * 2 + 100);      // ×2.00 + 100ms JVM
        assertThat(job.memoryLimitKb()).isEqualTo(262_144 + 131_072);
        assertThat(job.languageCode()).isEqualTo("java21");
        assertThat(job.attempt()).isEqualTo(1);
        assertThat(job.testdataVersion()).isEqualTo(5);
    }

    @Test
    void anh_chup_trang_thai_di_theo_ngay_sau_khi_claim_thang() {
        fakes.queue.nextClaim = claimedJob();

        useCase.claim(REQUEST);

        assertThat(fakes.submissions.judgingAttempt).isEqualTo(1);
        assertThat(fakes.calls).containsExactly("queue.claim", "submissions.markJudging");
    }

    /** Worker nhận source qua response của claim — quyết định B, và nó vẫn không cần DataSource. */
    @Test
    void job_mang_theo_source_va_khoa_cache_bien_dich() {
        fakes.queue.nextClaim = claimedJob();

        var job = useCase.claim(REQUEST).orElseThrow();

        assertThat(job.sourceContent()).isEqualTo(JudgingFakes.SOURCE);
        assertThat(job.sourceSha256()).isEqualTo(JudgingFakes.SHA);
        assertThat(job.toString()).doesNotContain("main", "return");   // bất biến #9
    }

    /**
     * ★ Tên file mã nguồn do API tính, từ {@code languages.source_extension}.
     *
     * <p>Với {@code java21} nó <b>phải</b> là {@code Main.java}: {@code run_command} trong
     * bảng {@code languages} viết {@code -cp {dir} Main}, nên lớp phải tên {@code Main} và
     * file phải tên {@code Main.java}. Sai tên thì mọi bài Java đều {@code CE}, với một thông
     * báo mà thí sinh không thể tự hiểu.
     *
     * <p>Trước khi có trường này, worker giữ một bảng tra {@code languageCode -> tên file} của
     * riêng nó — hai nguồn sự thật cho cùng một dữ kiện.
     */
    @Test
    void ten_file_ma_nguon_lay_tu_source_extension_cua_ngon_ngu() {
        fakes.queue.nextClaim = claimedJob();

        var job = useCase.claim(REQUEST).orElseThrow();

        assertThat(job.sourceFileName()).isEqualTo("Main.java");
    }

    /**
     * ★ Đề mất testdata: <b>không ném</b>. Ném là rollback, rollback là bài quay lại hàng đợi
     * tức thì và worker claim lại chính nó trong vài mili giây — một đề hỏng chiếm trọn năng
     * lực chấm của cả hệ thống. Giữ lease thì chỉ bài đó chờ reaper, phần còn lại chạy tiếp.
     */
    @Test
    void de_mat_testdata_thi_bo_qua_job_do_chu_khong_lam_dung_ca_hang_doi() {
        fakes.queue.nextClaim = claimedJob();
        var useCaseNoSpec = new ClaimJudgeJobUseCase(fakes.queue, fakes.submissions,
                specRepositoryReturning(null), JudgingFakes.properties());

        assertThatCode(() -> assertThat(useCaseNoSpec.claim(REQUEST)).isEmpty())
                .doesNotThrowAnyException();
    }

    private static JudgeSpecRepository specRepositoryReturning(JudgeSpec spec) {
        return (problemId, testdataVersion) -> Optional.ofNullable(spec);
    }
}
