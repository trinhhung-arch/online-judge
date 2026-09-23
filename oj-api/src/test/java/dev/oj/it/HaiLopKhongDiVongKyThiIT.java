package dev.oj.it;

import dev.oj.contests.application.usecase.GetContestUseCase;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.problems.application.port.ProblemRepository.ProblemListItem;
import dev.oj.problems.application.usecase.GetProblemUseCase;
import dev.oj.problems.application.usecase.ListProblemsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ★ ADMIN chưa bật 2FA KHÔNG đi vòng được cổng hai lớp qua {@code isAdmin()} — phần 2:
 * lịch thi và bảng xếp hạng. Cùng khuôn với {@link HaiLopKhongDiVongIT}: chưa bật thì bị
 * chặn, bật lại thì cùng lời gọi ấy qua.
 *
 * <p>Bốn chỗ ở đây đều là <b>công bằng kỳ thi</b>, thứ hệ thống này bán đầu tiên: xem đề
 * trước giờ mở, thấy nó trong danh sách, thấy danh sách đề của kỳ thi, và xem bảng xếp hạng
 * thật trong lúc đóng băng. Cả bốn đều là {@code @PublicAccess} — cổng cũ không bao giờ được
 * hỏi ở đây, kể cả khi cổng còn nằm đúng chỗ của nó.
 */
class HaiLopKhongDiVongKyThiIT extends PostgresIT {

    private static final String SLUG_CHUA_MO = "tuan-sau";

    @Autowired GetProblemUseCase getProblem;
    @Autowired ListProblemsUseCase listProblems;
    @Autowired GetContestUseCase getContest;
    @Autowired GetStandingsUseCase getStandings;

    @Test
    @DisplayName("★ xem đề của kỳ thi chưa mở — FR-CON-03")
    void xem_de_truoc_gio_mo() {
        datVaoKyThiChuaMo(PROBLEM_ID);

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lay(() -> getProblem.byCode("A-PLUS-B")))
                .hasFieldOrPropertyWithValue("code", "problem.not_found");

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> getProblem.byCode("A-PLUS-B")).id())
                .isEqualTo(PROBLEM_ID);
    }

    @Test
    @DisplayName("thấy đề của kỳ thi chưa mở trong danh sách đề")
    void danh_sach_de_truoc_gio_mo() {
        datVaoKyThiChuaMo(PROBLEM_ID);

        AdminHaiLop.go(jdbc);
        assertThat(maDeThay()).doesNotContain("A-PLUS-B");

        AdminHaiLop.bat(jdbc);
        assertThat(maDeThay()).contains("A-PLUS-B");
    }

    @Test
    @DisplayName("thấy danh sách đề trên trang của kỳ thi chưa mở")
    void de_cua_ky_thi_chua_mo() {
        datVaoKyThiChuaMo(PROBLEM_ID);

        AdminHaiLop.go(jdbc);
        assertThat(AdminHaiLop.lay(() -> getContest.theoSlug(SLUG_CHUA_MO)).cacDe()).isEmpty();

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> getContest.theoSlug(SLUG_CHUA_MO)).cacDe()).hasSize(1);
    }

    @Test
    @DisplayName("★ bảng xếp hạng thật trong lúc đóng băng — FR-CON-05")
    void bang_xep_hang_dong_bang() {
        long contestId = jdbc.sql("""
                INSERT INTO contests (slug, title, format, starts_at, freeze_at, ends_at,
                                      created_by)
                VALUES ('dang-dong-bang', 'Đang đóng băng', 'ICPC',
                        now() - interval '2 hours', now() - interval '1 hour',
                        now() + interval '1 hour', :setter)
                RETURNING id
                """).param("setter", SETTER_ID).query(Long.class).single();

        AdminHaiLop.go(jdbc);
        assertThat(AdminHaiLop.lay(() -> getStandings.thucHien(contestId)).dongBang())
                .as("chưa bật 2FA thì thấy bản đóng băng như mọi khán giả")
                .isTrue();

        AdminHaiLop.bat(jdbc);
        assertThat(AdminHaiLop.lay(() -> getStandings.thucHien(contestId)).dongBang())
                .isFalse();
    }

    // ---- sân -----------------------------------------------------------------------------

    private List<String> maDeThay() {
        return AdminHaiLop.lay(() -> listProblems.thucHien(null, null, null, 50)).items()
                .stream().map(ProblemListItem::code).toList();
    }

    private void datVaoKyThiChuaMo(long problemId) {
        long contestId = jdbc.sql("""
                INSERT INTO contests (slug, title, format, starts_at, ends_at, created_by)
                VALUES (:slug, 'Tuần sau', 'ICPC', now() + interval '1 day',
                        now() + interval '2 days', :setter)
                RETURNING id
                """).param("slug", SLUG_CHUA_MO).param("setter", SETTER_ID)
                .query(Long.class).single();
        jdbc.sql("INSERT INTO contest_problems (contest_id, problem_id, label) "
                        + "VALUES (:c, :p, 'A')")
                .param("c", contestId).param("p", problemId).update();
    }
}
