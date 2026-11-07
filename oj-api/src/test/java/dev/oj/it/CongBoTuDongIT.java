package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.domain.Contest;
import dev.oj.platform.security.GiaLapDanhTinh;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CongBoTuDongIT extends PostgresIT {

    @Autowired ContestRepository contests;
    @Autowired StandingsUpdater updater;

    private final Instant bayGio = Instant.now();

    private long kyThiDaXong(boolean tuCongBo) {
        return contests.tao(new ContestRepository.ContestMoi("cong-bo-" + System.nanoTime(),
                "Thi thử", "ICPC", bayGio.minus(Duration.ofHours(3)),
                bayGio.minus(Duration.ofMinutes(10)), bayGio.minus(Duration.ofHours(1)), 20, true,
                tuCongBo, ADMIN_ID));
    }

    private void nhipNhuBoLapLich() {
        try (var khach = GiaLapDanhTinh.khach()) {
            updater.nhip();
            assertThat(khach).isNotNull();
        }
    }

    @Test
    @DisplayName("★ DB-13 · revealAfterEnd bật: bảng chốt là tự công bố — hết đóng băng, audit ghi người làm là hệ thống")
    void tu_cong_bo_khi_chot_bang() {
        long id = kyThiDaXong(true);

        nhipNhuBoLapLich();

        Contest contest = contests.timTheoId(id).orElseThrow();
        assertThat(contest.daCongBo()).isTrue();
        assertThat(contest.dangDongBang(Instant.now())).isFalse();
        assertThat(contests.canCapNhat(Instant.now())).extracting(Contest::id).doesNotContain(id);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit_log
                 WHERE action = 'CONTEST_STANDINGS_REVEALED' AND entity_id = :c
                   AND actor_id IS NULL
                """).param("c", id).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB-13 · revealAfterEnd tắt: bảng vẫn chốt nhưng còn đóng băng, chờ ADMIN công bố")
    void tat_tu_cong_bo_thi_cho_admin() {
        long id = kyThiDaXong(false);

        nhipNhuBoLapLich();

        Contest contest = contests.timTheoId(id).orElseThrow();
        assertThat(contest.daCongBo()).isFalse();
        assertThat(contest.dangDongBang(Instant.now())).isTrue();
        assertThat(contests.canCapNhat(Instant.now())).extracting(Contest::id).doesNotContain(id);
    }
}
