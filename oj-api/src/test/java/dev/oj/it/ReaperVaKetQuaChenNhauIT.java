package dev.oj.it;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.contract.Verdict;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.ReapStaleJobsUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase.Outcome;
import dev.oj.judging.application.usecase.SubmitSolutionUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReaperVaKetQuaChenNhauIT extends PostgresIT {

    private static final ClaimRequestDto MAY = ClaimRequestDto.single("mac-m1max-host", "arm64");
    private static final String CAU_THA_VE_QUEUED = "SET status = 'QUEUED'";
    private static final String CAU_GHI_LUOT_CHAM = "INSERT INTO judge_runs";

    @Autowired SubmitSolutionUseCase submitSolution;
    @Autowired ClaimJudgeJobUseCase claimJudgeJob;
    @Autowired RecordJudgeResultUseCase recordJudgeResult;
    @Autowired ReapStaleJobsUseCase reapStaleJobs;

    private final List<Throwable> loiTrongLuong = new CopyOnWriteArrayList<>();

    @AfterEach
    void huyDiemHen() {
        DiemHenSql.huyHet();
    }

    @Test
    @DisplayName("★ KQ-05 · reaper khoá dòng trước, kết quả đứng chờ khoá → kết quả bị bỏ qua, bài được chấm lại")
    void reaper_thang_truoc() throws InterruptedException {
        long id = baiDaHetLease();
        AtomicInteger daTha = new AtomicInteger(-1);
        AtomicReference<Outcome> ghi = new AtomicReference<>();
        CountDownLatch reaperDangGiuKhoa = new CountDownLatch(1);

        Thread reaper = luong("reaper", () -> daTha.set(reapStaleJobs.reap()));
        Thread worker = luong("worker", () -> {
            cho(reaperDangGiuKhoa);
            ghi.set(recordJudgeResult.record(ketQua(id, 1)));
        });
        DiemHenSql.hen(reaper, CAU_THA_VE_QUEUED, reaperDangGiuKhoa,
                () -> dangChoKhoa("%DELETE FROM judge_queue%"));

        chayVaChoXong(reaper, worker);

        assertThat(daTha.get()).isEqualTo(1);
        assertThat(ghi.get())
                .as("câu khoá chạy lại trên dòng reaper vừa thả, thấy claimed_at rỗng")
                .isEqualTo(Outcome.IGNORED);
        assertThat(status(id)).isEqualTo("QUEUED");
        assertThat(soDongChoNhan(id)).as("bài còn trong hàng đợi, chờ người nhận").isEqualTo(1);
        assertThat(soBanGhiCham(id)).isZero();

        assertThat(claimJudgeJob.claim(MAY).orElseThrow().attempt()).isEqualTo(2);
        assertThat(recordJudgeResult.record(ketQua(id, 2))).isEqualTo(Outcome.RECORDED);
        assertThat(status(id)).isEqualTo("DONE");
    }

    @Test
    @DisplayName("★ KQ-05 · kết quả khoá dòng trước, reaper đứng chờ khoá → verdict được ghi, reaper không thả gì")
    void ket_qua_thang_truoc() throws InterruptedException {
        long id = baiDaHetLease();
        AtomicInteger daTha = new AtomicInteger(-1);
        AtomicReference<Outcome> ghi = new AtomicReference<>();
        CountDownLatch ketQuaDangGiuKhoa = new CountDownLatch(1);

        Thread worker = luong("worker", () -> ghi.set(recordJudgeResult.record(ketQua(id, 1))));
        Thread reaper = luong("reaper", () -> {
            cho(ketQuaDangGiuKhoa);
            daTha.set(reapStaleJobs.reap());
        });
        DiemHenSql.hen(worker, CAU_GHI_LUOT_CHAM, ketQuaDangGiuKhoa,
                () -> dangChoKhoa("%reap_count%"));

        chayVaChoXong(worker, reaper);

        assertThat(ghi.get()).isEqualTo(Outcome.RECORDED);
        assertThat(daTha.get()).as("reaper chạy lại trên dòng đã bị xoá, không thả gì").isZero();
        assertThat(status(id)).isEqualTo("DONE");
        assertThat(verdict(id)).isEqualTo("AC");
        assertThat(soBanGhiCham(id)).isEqualTo(1);
        assertThat(soDongHangDoi(id)).isZero();
    }

    private long baiDaHetLease() {
        long id = submitSolution.submit(new SubmitSolutionUseCase.Command(
                PROBLEM_ID, "cpp20", "// EXPECT: AC\nint main(){}")).submissionId();
        assertThat(claimJudgeJob.claim(MAY).orElseThrow().attempt()).isEqualTo(1);
        jdbc.sql("""
                UPDATE judge_queue SET lease_until = now() - interval '1 second'
                 WHERE submission_id = :id
                """).param("id", id).update();
        return id;
    }

    private static JudgeResultDto ketQua(long id, int attempt) {
        return new JudgeResultDto(id, attempt, Verdict.AC, 100, 100, null, 3, 23, 8192,
                null, null, "mac-m1max-host", new BigDecimal("1.000"), Instant.now(), List.of());
    }

    private boolean dangChoKhoa(String mauCauLenh) {
        return Boolean.TRUE.equals(jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                                WHERE wait_event_type = 'Lock' AND query LIKE :mau)
                """).param("mau", mauCauLenh).query(Boolean.class).single());
    }

    private String status(long id) {
        return jdbc.sql("""
                SELECT status FROM submissions WHERE id = :id
                """).param("id", id).query(String.class).single();
    }

    private String verdict(long id) {
        return jdbc.sql("""
                SELECT verdict FROM submissions WHERE id = :id
                """).param("id", id).query(String.class).optional().orElse(null);
    }

    private int soDongChoNhan(long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM judge_queue
                 WHERE submission_id = :id AND claimed_at IS NULL
                """).param("id", id).query(Integer.class).single();
    }

    private int soDongHangDoi(long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM judge_queue WHERE submission_id = :id
                """).param("id", id).query(Integer.class).single();
    }

    private int soBanGhiCham(long id) {
        return jdbc.sql("""
                SELECT count(*)::int FROM judge_runs WHERE submission_id = :id
                """).param("id", id).query(Integer.class).single();
    }

    private Thread luong(String ten, Runnable viec) {
        return new Thread(() -> {
            try {
                viec.run();
            } catch (Throwable e) {
                loiTrongLuong.add(e);
            }
        }, ten);
    }

    private void chayVaChoXong(Thread... cac) throws InterruptedException {
        for (Thread t : cac) {
            t.start();
        }
        for (Thread t : cac) {
            t.join(DiemHenSql.TRAN_CHO.multipliedBy(3).toMillis());
            assertThat(t.isAlive()).as("luồng %s treo", t.getName()).isFalse();
        }
        assertThat(loiTrongLuong).as("lỗi trong luồng đua").isEmpty();
    }

    private static void cho(CountDownLatch latch) {
        try {
            if (!latch.await(DiemHenSql.TRAN_CHO.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("luồng kia không tới điểm hẹn sau " + DiemHenSql.TRAN_CHO);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
