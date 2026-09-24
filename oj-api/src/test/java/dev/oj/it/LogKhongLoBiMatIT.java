package dev.oj.it;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Bất biến #9 trên log THẬT (bao-mat-plan Lỗ 3, bước 1.2) — lưới thứ hai sau
 * {@code LogKhongBiMatTest}.
 *
 * <p>Gắn một {@link ListAppender} vào logger gốc, hạ {@code dev.oj} xuống TRACE (bắt cả dòng
 * debug mà prod không in), rồi chạy trọn đường nộp bài → claim → tiến độ → verdict, cả nhánh
 * lỗi biên dịch (log compiler thường chép lại dòng mã lỗi), và một lượt đăng nhập. Mã nguồn,
 * log compiler, mật khẩu đều mang một chuỗi mốc; không dòng log nào — kể cả stack trace — được
 * chứa nó.
 */
class LogKhongLoBiMatIT extends HttpIT {

    private static final String MOC = "MOC_BI_MAT_7c1f9e";

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeProgressUseCase recordProgress;
    @Autowired RecordJudgeResultUseCase recordResult;

    private final ListAppender<ILoggingEvent> nghe = new ListAppender<>();
    private Level mucCu;

    @BeforeEach
    void ganTai() {
        Logger oj = (Logger) LoggerFactory.getLogger("dev.oj");
        mucCu = oj.getLevel();
        oj.setLevel(Level.TRACE);
        nghe.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(nghe);
    }

    @AfterEach
    void goTai() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(nghe);
        ((Logger) LoggerFactory.getLogger("dev.oj")).setLevel(mucCu);
    }

    @Test
    @DisplayName("★ nộp bài → tiến độ → AC: mã nguồn không xuất hiện trong dòng log nào")
    void duong_nop_bai_khong_ghi_ma_nguon() {
        long id = nop();
        int attempt = claim(id);
        recordProgress.record(new JudgeProgressDto(id, attempt, 1, 2, 3,
                List.of(new JudgeProgressDto.TestOutcome(1, Verdict.AC, 12, 2048))));
        recordResult.record(new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 234, 4096,
                null, "OK", "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of()));

        khongLo();
    }

    @Test
    @DisplayName("★ lỗi biên dịch: log compiler (chép lại dòng mã) không đi vào log hệ thống")
    void loi_bien_dich_khong_ghi_log_compiler() {
        long id = nop();
        int attempt = claim(id);
        recordResult.record(JudgeResultDto.compileError(id, attempt, 100, "mac-m1max-host",
                new BigDecimal("1.000"), Instant.now(), "a.cpp:1: error: '" + MOC + "' was not declared"));

        khongLo();
    }

    /**
     * Qua HTTP thật, không gọi thẳng use-case: use-case chỉ NÉM, dòng log sinh ra ở
     * {@code GlobalExceptionHandler} — gọi thẳng thì không có dòng nào để soi (đo 2026-09-24:
     * chốt "phải bắt được log" đỏ ở bản đầu, đúng như nó được đặt ra để làm).
     */
    @Test
    @DisplayName("★ đăng nhập sai qua HTTP: mật khẩu đã thử không xuất hiện trong log")
    void dang_nhap_khong_ghi_mat_khau() {
        assertThat(login("dev", MOC).getStatusCode().value()).isEqualTo(401);

        khongLo();
    }

    private long nop() {
        return submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// " + MOC + "\nint main(){}")).submissionId();
    }

    private int claim(long id) {
        var job = claimJudgeJob.claim(ClaimRequestDto.single("mac-m1max-host", "arm64"))
                .orElseThrow(() -> new AssertionError("hàng đợi rỗng"));
        assertThat(job.submissionId()).isEqualTo(id);
        return job.attempt();
    }

    private void khongLo() {
        assertThat(nghe.list).as("không bắt được dòng log nào — appender không gắn được, ca này không đo gì")
                .isNotEmpty();
        for (ILoggingEvent e : nghe.list) {
            String toan = e.getFormattedMessage()
                    + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            assertThat(toan).as("[%s] %s", e.getLevel(), e.getLoggerName()).doesNotContain(MOC);
        }
    }
}
