package dev.oj.it;

import dev.oj.contests.application.FreezeStandingsScheduler;
import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.infrastructure.JdbcStandingsReader;
import dev.oj.judging.application.usecase.HideSubmissionUseCase;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BaiAnVaBangXepHangIT extends PostgresIT {

    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired JdbcStandingsReader bang;
    @Autowired StandingsUpdater updater;
    @Autowired FreezeStandingsScheduler freeze;
    @Autowired GetStandingsUseCase getStandings;
    @Autowired HideSubmissionUseCase hide;

    private final Instant bayGio = Instant.now();

    @Test
    @DisplayName("★ M1 · ADMIN ẩn bài AC đã lên bảng giữa giờ thi → dòng của người đó tính lại ngay, nhịp sau không cộng lại; hiện lại thì điểm quay về")
    void an_bai_da_len_bang_thi_tru_ngay() {
        long id = kyThi(null);
        long gianLan = nguoi("gian-lan");
        long ngayThang = nguoi("ngay-thang");
        nop(id, gianLan, "WA", 0, 50);
        long ac = nop(id, gianLan, "AC", 100, 40);
        nop(id, ngayThang, "AC", 100, 30);
        updater.capNhat(doc(id));
        assertThat(dong(id, gianLan, false).soBaiDat()).isEqualTo(1);
        assertThat(bang.top(id, 50, false)).hasSize(2);

        anHien(ac, true);

        StandingsReader.Dong sauKhiAn = dong(id, gianLan, false);
        assertThat(sauKhiAn.soBaiDat()).as("bài AC bị ẩn không được tính").isZero();
        assertThat(sauKhiAn.tongDiem()).isZero();
        assertThat(bang.hang(id, ngayThang, false)).hasValue(1);
        assertThat(updater.capNhat(doc(id))).as("nhịp sau không có gì mới để cộng").isZero();
        assertThat(dong(id, gianLan, false).soBaiDat()).isZero();

        anHien(ac, false);

        assertThat(dong(id, gianLan, false).soBaiDat()).isEqualTo(1);
        assertThat(dong(id, gianLan, false).penaltyGiay())
                .as("hiện lại thì lần sai trước đó cũng quay về như cũ").isPositive();
    }

    @Test
    @DisplayName("★ M1 · bài bị ẩn TRƯỚC khi lên bảng thì không bao giờ được cộng; người chỉ có bài bị ẩn không có dòng")
    void an_truoc_khi_len_bang() {
        long id = kyThi(null);
        long chiCoBaiAn = nguoi("chi-co-bai-an");
        long ac = nop(id, chiCoBaiAn, "AC", 100, 30);

        anHien(ac, true);
        updater.capNhat(doc(id));

        assertThat(bang.cuaNguoi(id, chiCoBaiAn, false)).isEmpty();
        assertThat(bang.top(id, 50, false)).isEmpty();
    }

    @Test
    @DisplayName("★ M1 · đang đóng băng: ẩn bài nộp trước giờ đóng băng thì bản chụp công khai cũng mất bài đó")
    void an_bai_trong_ban_chup_dong_bang() {
        long id = kyThi(bayGio.minus(Duration.ofMinutes(20)));
        long nguoiA = nguoi("truoc-dong-bang");
        long ac = nop(id, nguoiA, "AC", 100, 40);
        updater.capNhat(doc(id));
        freeze.nhip();
        assertThat(dong(id, nguoiA, true).soBaiDat()).isEqualTo(1);

        anHien(ac, true);

        assertThat(bang.cuaNguoi(id, nguoiA, true))
                .as("người xem thường thấy bản chụp — bài bị ẩn không được nằm trong đó")
                .isEmpty();
        assertThat(bang.top(id, 50, true)).isEmpty();
        assertThat(bang.cuaNguoi(id, nguoiA, false)).isEmpty();
    }

    @Test
    @DisplayName("★ M1 · bảng sau khi tính lại một người TRÙNG với bảng dựng lại từ đầu")
    void tinh_lai_mot_nguoi_bang_dung_lai_tu_dau() {
        long id = kyThi(null);
        long a = nguoi("nguoi-a");
        long b = nguoi("nguoi-b");
        nop(id, a, "WA", 0, 50);
        long acA = nop(id, a, "AC", 100, 45);
        nop(id, b, "TLE", 0, 40);
        nop(id, b, "AC", 100, 35);
        nop(id, a, "AC", 100, 20);
        updater.capNhat(doc(id));

        anHien(acA, true);
        List<StandingsReader.Dong> sauTinhLai = bang.top(id, 50, false);

        updater.xoaTrang(id);
        updater.capNhat(doc(id));

        assertThat(bang.top(id, 50, false)).usingRecursiveFieldByFieldElementComparator()
                .containsExactlyElementsOf(sauTinhLai);
    }

    @Test
    @DisplayName("★ M5 · đăng ký mà không nộp bài nào: không có điểm, không có dòng — trên bảng thật lẫn bản chụp, và 'dòng của tôi' trống")
    void dang_ky_ma_khong_nop() {
        long id = kyThi(bayGio.minus(Duration.ofMinutes(20)));
        contests.dangKy(id, USER_ID, bayGio.minus(Duration.ofHours(3)));
        long coNop = nguoi("co-nop-bai");
        nop(id, coNop, "AC", 100, 40);
        updater.capNhat(doc(id));
        freeze.nhip();

        for (boolean dongBang : new boolean[]{false, true}) {
            assertThat(bang.top(id, 50, dongBang)).extracting(StandingsReader.Dong::userId)
                    .as("đóng băng = %s", dongBang).containsExactly(coNop);
            assertThat(bang.cuaNguoi(id, USER_ID, dongBang)).isEmpty();
            assertThat(bang.hang(id, USER_ID, dongBang)).isEmpty();
        }
        assertThat(getStandings.thucHien(id).dongCuaToi()).isEmpty();
        assertThat(getStandings.thucHien(id).hangCuaToi()).isNull();
    }

    private void anHien(long submissionId, boolean an) {
        try (var admin = GiaLapDanhTinh.dongVai(ADMIN_ID, "admin", Role.ADMIN)) {
            hide.dat(submissionId, an);
            assertThat(admin).isNotNull();
        }
    }

    private StandingsReader.Dong dong(long contestId, long userId, boolean dongBang) {
        return bang.cuaNguoi(contestId, userId, dongBang).orElseThrow();
    }

    private long kyThi(Instant dongBang) {
        long id = contests.tao(new ContestRepository.ContestMoi("bai-an-" + System.nanoTime(),
                "Thi thử", "ICPC", bayGio.minus(Duration.ofHours(2)),
                bayGio.plus(Duration.ofHours(1)), dongBang, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private Contest doc(long id) {
        return contests.timTheoId(id).orElseThrow();
    }

    private long nguoi(String handle) {
        return jdbc.sql("""
                INSERT INTO users (handle, email, display_name) VALUES (:h, :email, :h)
                RETURNING id
                """).param("h", handle).param("email", handle + "@oj.test")
                .query(Long.class).single();
    }

    private long nop(long contestId, long userId, String verdict, int score, int phutTruoc) {
        jdbc.sql("""
                INSERT INTO source_blobs (sha256, content, byte_size)
                VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING
                """).param("sha", NGUON).update();
        return jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, judged_at,
                                         created_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, :v, :diem, 100, now(),
                        now() - make_interval(mins => :phut))
                RETURNING id
                """).param("u", userId).param("p", PROBLEM_ID).param("c", contestId)
                .param("sha", NGUON).param("v", verdict).param("diem", score)
                .param("phut", phutTruoc).query(Long.class).single();
    }
}
