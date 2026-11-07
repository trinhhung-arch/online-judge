package dev.oj.it;

import dev.oj.contests.application.StandingsUpdater;
import dev.oj.contests.application.port.ContestAuthoringRepository;
import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.contests.domain.Contest;
import dev.oj.contests.infrastructure.JdbcContestWindowQuery;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.ImportTestdataUseCase;
import dev.oj.problems.domain.ProblemsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KhungGioKyThiIT extends PostgresIT {

    private static final Instant BAT_DAU = Instant.parse("2031-03-01T09:00:00Z");
    private static final Instant KET_THUC = BAT_DAU.plus(Duration.ofHours(5));
    private static final Duration MOT_GIAY = Duration.ofSeconds(1);
    private static final String NGUON = "a".repeat(64);

    @Autowired ContestRepository contests;
    @Autowired ContestAuthoringRepository soanKyThi;
    @Autowired StandingsUpdater updater;
    @Autowired StandingsReader standings;
    @Autowired ImportTestdataUseCase importTestdata;
    @Autowired AuthorContestUseCase author;

    private long kyThi(Instant batDau, Instant ketThuc) {
        long id = contests.tao(new ContestRepository.ContestMoi("khung-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, null, 20, true, true, ADMIN_ID));
        soanKyThi.themDe(id, PROBLEM_ID, "A", 100, ADMIN_ID, true);
        return id;
    }

    private JdbcContestWindowQuery luc(Instant t) {
        return new JdbcContestWindowQuery(jdbc, Clock.fixed(t, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("★ NB-02 · nộp đúng starts_at và ends_at − 1s thì gắn kỳ thi; đúng ends_at thì thành bài luyện tập")
    void nb02_gan_ky_thi_theo_tung_giay() {
        long id = kyThi(BAT_DAU, KET_THUC);

        assertThat(luc(BAT_DAU).contestDangChayChuaDe(PROBLEM_ID)).hasValue(id);
        assertThat(luc(KET_THUC.minus(MOT_GIAY)).contestDangChayChuaDe(PROBLEM_ID)).hasValue(id);
        assertThat(luc(KET_THUC).contestDangChayChuaDe(PROBLEM_ID)).isEmpty();
    }

    @Test
    @DisplayName("★ KG-05 · đã đăng ký: khoá tới starts_at − 1s, mở đúng starts_at · chưa đăng ký: khoá tới ends_at − 1s, mở đúng ends_at")
    void kg05_mo_khoa_de_theo_tung_giay() {
        long id = kyThi(BAT_DAU, KET_THUC);
        contests.dangKy(id, USER_ID, BAT_DAU.minus(Duration.ofDays(1)));
        long chuaDangKy = jdbc.sql("""
                INSERT INTO users (handle, email, display_name)
                VALUES ('chua-dang-ky', 'chua-dang-ky@oj.test', 'Chưa đăng ký')
                RETURNING id
                """).query(Long.class).single();

        assertThat(luc(BAT_DAU.minus(MOT_GIAY)).deBiKhoaBoiLichThi(PROBLEM_ID, USER_ID, false))
                .as("đã đăng ký, còn một giây nữa mới mở").isTrue();
        assertThat(luc(BAT_DAU).deBiKhoaBoiLichThi(PROBLEM_ID, USER_ID, false)).isFalse();
        assertThat(luc(KET_THUC.minus(MOT_GIAY)).deBiKhoaBoiLichThi(PROBLEM_ID, chuaDangKy, false))
                .as("chưa đăng ký, kỳ thi còn một giây").isTrue();
        assertThat(luc(KET_THUC).deBiKhoaBoiLichThi(PROBLEM_ID, chuaDangKy, false)).isFalse();
    }

    @Test
    @DisplayName("★ NB-04 · nộp trước giờ kết thúc, chấm xong sau giờ kết thúc (trong ân hạn) → vẫn vào bảng")
    void nb04_cham_xong_sau_gio_van_vao_bang() {
        Instant ketThuc = Instant.now().minus(Duration.ofMinutes(1));
        long id = kyThi(ketThuc.minus(Duration.ofHours(2)), ketThuc);
        jdbc.sql("INSERT INTO source_blobs (sha256, content, byte_size) "
                + "VALUES (:sha, 'int main(){}', 12) ON CONFLICT DO NOTHING")
                .param("sha", NGUON).update();
        jdbc.sql("""
                INSERT INTO submissions (user_id, problem_id, contest_id, language_id,
                                         source_sha256, source_bytes, testdata_version, status,
                                         attempt, verdict, score, max_score, created_at, judged_at)
                VALUES (:u, :p, :c, 1, :sha, 12, 1, 'DONE', 1, 'AC', 100, 100, :nop, now())
                """)
                .param("u", USER_ID).param("p", PROBLEM_ID).param("c", id).param("sha", NGUON)
                .param("nop", OffsetDateTime.ofInstant(ketThuc.minus(Duration.ofMinutes(10)),
                        ZoneOffset.UTC))
                .update();

        assertThat(contests.canCapNhat(Instant.now()))
                .extracting(Contest::id).as("hết giờ một phút vẫn nằm trong ân hạn").contains(id);
        updater.nhip();

        assertThat(standings.top(id, 50, false)).singleElement()
                .satisfies(d -> assertThat(d.soBaiDat()).isEqualTo(1));
    }

    @Test
    @DisplayName("★ AT-14 / NB-06 · gắn đề vào kỳ thi chồng giờ với một kỳ thi khác đang có đề ấy → 400; kỳ thi nối đuôi (bắt đầu đúng lúc kia kết thúc) thì được")
    void at14_khong_gan_mot_de_vao_hai_ky_thi_chong_gio() {
        Instant moc = Instant.now().plus(Duration.ofDays(1));
        long a = kyThiCuaSetter(moc, moc.plus(Duration.ofHours(3)));
        soanKyThi.themDe(a, PROBLEM_ID, "A", 100, SETTER_ID, false);
        long chong = kyThiCuaSetter(moc.plus(Duration.ofHours(1)), moc.plus(Duration.ofHours(4)));
        long noiDuoi = kyThiCuaSetter(moc.plus(Duration.ofHours(3)), moc.plus(Duration.ofHours(5)));

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThatThrownBy(() -> author.themDe(chong, PROBLEM_ID, "A", 100))
                    .hasFieldOrPropertyWithValue("code", "contest.de_trung_khung_gio");
            author.themDe(noiDuoi, PROBLEM_ID, "A", 100);
            assertThat(phien).isNotNull();
        }

        assertThat(contests.deCua(chong)).isEmpty();
        assertThat(contests.deCua(noiDuoi)).extracting(ContestRepository.DeCuaContest::problemId)
                .containsExactly(PROBLEM_ID);
    }

    private long kyThiCuaSetter(Instant batDau, Instant ketThuc) {
        return contests.tao(new ContestRepository.ContestMoi("chong-gio-" + System.nanoTime(),
                "Thi thử", "ICPC", batDau, ketThuc, null, 20, true, true, SETTER_ID));
    }

    @Test
    @DisplayName("★ LT-03 · nạp testdata cho đề đang thi → 409 problem.dang_trong_ky_thi, testdata không đổi")
    void lt03_khong_nap_testdata_cho_de_dang_thi() {
        Instant bayGio = Instant.now();
        kyThi(bayGio.minus(Duration.ofHours(1)), bayGio.plus(Duration.ofHours(2)));

        try (var phien = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            assertThatThrownBy(() -> importTestdata.thucHien(PROBLEM_ID,
                    new ByteArrayInputStream(new byte[] {'P', 'K'}), 2))
                    .isInstanceOf(ProblemsException.class)
                    .hasFieldOrPropertyWithValue("kind", DomainException.Kind.CONFLICT)
                    .hasFieldOrPropertyWithValue("code", "problem.dang_trong_ky_thi");
            assertThat(phien).isNotNull();
        }

        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE type = 'TESTDATA_IMPORT'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT current_testdata_version FROM problems WHERE id = :id")
                .param("id", PROBLEM_ID).query(Integer.class).single()).isEqualTo(1);
    }
}
