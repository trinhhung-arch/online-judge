package dev.oj.it;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.usecase.AuthorContestUseCase;
import dev.oj.platform.contest.ContestWindowQuery;
import dev.oj.platform.error.DomainException;
import dev.oj.platform.security.GiaLapDanhTinh;
import dev.oj.platform.security.Role;
import dev.oj.problems.application.usecase.AuthorProblemUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ★ Chỉ chủ kỳ thi sửa được kỳ thi, chỉ chủ đề gắn được đề — ADMIN thì mọi thứ.
 *
 * <h2>Lỗ đã có thật (rà bảo mật 2026-09-23)</h2>
 * {@code AuthorContestUseCase} chỉ có {@code @RequiresRole(SETTER)}, và câu SQL gắn/gỡ đề
 * không nhận người gọi. SETTER bất kỳ gỡ được đề khỏi kỳ thi sắp diễn ra của người khác, và
 * giấu được đề công khai của người khác bằng một kỳ thi tự tạo kéo tới năm 2099.
 *
 * <h2>Mỗi ca chặn ĐÚNG MỘT điều kiện</h2>
 * "Kẻ khác" ({@code USER_ID} đóng vai SETTER) có kỳ thi riêng và đề riêng. Ca "kỳ thi của
 * người khác" dùng đề CỦA KẺ ẤY, ca "đề của người khác" dùng kỳ thi CỦA KẺ ẤY — để mỗi lần
 * bị chặn chỉ có một lý do, và lý do ấy đúng là thứ ca muốn đo.
 */
class ContestChuSoHuuIT extends HttpIT {

    @Autowired ContestRepository contests;
    @Autowired AuthorContestUseCase author;
    @Autowired ContestWindowQuery lichThi;

    private static final Instant MOC = Instant.now();

    @Test
    @DisplayName("★ SETTER khác gắn/đổi nhãn đề trong kỳ thi của người khác → 404, không đổi gì")
    void ke_khac_gan_de_vao_ky_thi_nguoi_khac() {
        long ky = kyThiCua(SETTER_ID);
        try (var chu = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            author.themDe(ky, PROBLEM_ID, "A", 100);
        }
        long deCuaKeKhac = deCongKhaiCua(USER_ID);

        try (var keKhac = GiaLapDanhTinh.dongVai(USER_ID, "ke-khac", Role.SETTER)) {
            assertThatThrownBy(() -> author.themDe(ky, deCuaKeKhac, "B", 100))
                    .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
            // Gắn lại một đề đã có = đổi nhãn/điểm (ON CONFLICT DO UPDATE) — cùng chốt.
            assertThatThrownBy(() -> author.themDe(ky, PROBLEM_ID, "Z", 999))
                    .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
        }

        assertThat(deTrongKyThi(ky)).containsExactly(Map.entry(PROBLEM_ID, "A:100"));
    }

    @Test
    @DisplayName("★ SETTER khác gỡ đề khỏi kỳ thi của người khác → 404, đề còn nguyên")
    void ke_khac_go_de_khoi_ky_thi_nguoi_khac() {
        long ky = kyThiCua(SETTER_ID);
        try (var chu = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            author.themDe(ky, PROBLEM_ID, "A", 100);
        }

        try (var keKhac = GiaLapDanhTinh.dongVai(USER_ID, "ke-khac", Role.SETTER)) {
            assertThatThrownBy(() -> author.goDeKhoiKyThi(ky, PROBLEM_ID))
                    .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
        }

        assertThat(deTrongKyThi(ky)).containsKey(PROBLEM_ID);
    }

    @Test
    @DisplayName("SETTER khác soạn đề riêng vào kỳ thi của người khác → 404, KHÔNG để lại đề mồ côi")
    void ke_khac_soan_de_rieng_vao_ky_thi_nguoi_khac() {
        long ky = kyThiCua(SETTER_ID);
        int soDeTruoc = soDe();

        try (var keKhac = GiaLapDanhTinh.dongVai(USER_ID, "ke-khac", Role.SETTER)) {
            assertThatThrownBy(() -> author.soanDeRieng(ky, new AuthorProblemUseCase.Command(
                    "DE-CHEN-NGANG", "Đề chen ngang", "x", 1000, 262_144,
                    dev.oj.contract.CheckerType.TOKEN, null,
                    dev.oj.contract.ScoringMode.ALL_OR_NOTHING,
                    dev.oj.problems.domain.FeedbackLevel.TEST_INDEX, false), "B", 100))
                    .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");
        }

        assertThat(soDe()).as("bị từ chối thì không được sinh ra đề DRAFT nào").isEqualTo(soDeTruoc);
        assertThat(deTrongKyThi(ky)).isEmpty();
    }

    /**
     * Đường giấu đề: gắn đề công khai của người khác vào kỳ thi của mình. Bị chặn, VÀ câu trả
     * lời giống hệt câu cho một id không có thật — nếu không, lỗi này thành cách dò đề DRAFT.
     */
    @Test
    @DisplayName("★ không gắn được đề của người khác vào kỳ thi của mình — cùng câu với id rác")
    void khong_gan_duoc_de_cua_nguoi_khac() {
        long kyCuaMinh = kyThiCua(USER_ID);

        DomainException deNguoiKhac;
        DomainException idRac;
        try (var keKhac = GiaLapDanhTinh.dongVai(USER_ID, "ke-khac", Role.SETTER)) {
            deNguoiKhac = catchThrowableOfType(DomainException.class,
                    () -> author.themDe(kyCuaMinh, PROBLEM_ID, "A", 100));
            idRac = catchThrowableOfType(DomainException.class,
                    () -> author.themDe(kyCuaMinh, 999_999L, "A", 100));
        }

        assertThat(deNguoiKhac.code()).isEqualTo("contest.de_khong_ton_tai");
        assertThat(deNguoiKhac.publicMessage().replace(String.valueOf(PROBLEM_ID), "#"))
                .as("đề có thật của người khác và id không có thật phải KHÔNG phân biệt được")
                .isEqualTo(idRac.publicMessage().replace("999999", "#"));
        assertThat(lichThi.deNamTrongKyThiNaoDo(PROBLEM_ID))
                .as("đề của setter không bị kéo vào kỳ thi nào — nên không bị giấu khỏi kho")
                .isFalse();
    }

    @Test
    @DisplayName("chủ kỳ thi gắn, đổi nhãn, gỡ đề của chính mình — vẫn làm được như cũ")
    void chu_ky_thi_van_soan_duoc() {
        long ky = kyThiCua(SETTER_ID);

        try (var chu = GiaLapDanhTinh.dongVai(SETTER_ID, "setter", Role.SETTER)) {
            author.themDe(ky, PROBLEM_ID, "A", 100);
            author.themDe(ky, PROBLEM_ID, "B", 250);
            assertThat(deTrongKyThi(ky)).containsExactly(Map.entry(PROBLEM_ID, "B:250"));

            author.goDeKhoiKyThi(ky, PROBLEM_ID);
        }
        assertThat(deTrongKyThi(ky)).isEmpty();
    }

    @Test
    @DisplayName("ADMIN soạn được kỳ thi và đề của người khác — nhưng chỉ khi đã bật 2FA (ADR 017)")
    void admin_soan_duoc_moi_ky_thi() {
        long ky = kyThiCua(SETTER_ID);

        AdminHaiLop.go(jdbc);
        assertThatThrownBy(() -> AdminHaiLop.lam(() -> author.themDe(ky, PROBLEM_ID, "A", 100)))
                .hasFieldOrPropertyWithValue("code", "contest.khong_tim_thay");

        AdminHaiLop.bat(jdbc);
        AdminHaiLop.lam(() -> author.themDe(ky, PROBLEM_ID, "A", 100));
        assertThat(deTrongKyThi(ky)).containsKey(PROBLEM_ID);
    }

    @Test
    @DisplayName("★ qua HTTP thật: USER → 403, SETTER khác → 404, chủ kỳ thi → 204")
    void qua_http() {
        long ky = kyThiCua(SETTER_ID);
        String gan = "/api/v1/contests/{id}/problems";
        Map<String, Object> than = Map.of("problemId", PROBLEM_ID, "label", "A");

        assertThat(goi(http.post().uri(gan, ky).body(than).header(HttpHeaders.AUTHORIZATION,
                bearer(USER_ID, "dev", Role.USER))).getStatusCode().value()).isEqualTo(403);
        assertThat(goi(http.post().uri(gan, ky).body(than).header(HttpHeaders.AUTHORIZATION,
                bearer(USER_ID, "ke-khac", Role.SETTER))).getStatusCode().value()).isEqualTo(404);
        assertThat(deTrongKyThi(ky)).isEmpty();

        assertThat(goi(http.post().uri(gan, ky).body(than).header(HttpHeaders.AUTHORIZATION,
                bearer(SETTER_ID, "setter", Role.SETTER))).getStatusCode().value()).isEqualTo(204);
        assertThat(goi(http.delete().uri(gan + "/{p}", ky, PROBLEM_ID)
                .header(HttpHeaders.AUTHORIZATION, bearer(USER_ID, "ke-khac", Role.SETTER)))
                .getStatusCode().value()).isEqualTo(404);
        assertThat(deTrongKyThi(ky)).containsKey(PROBLEM_ID);

        // Giao diện chỉ hiện khu gắn/gỡ đề cho người soạn được — cờ do server tính.
        String slug = jdbc.sql("SELECT slug FROM contests WHERE id = :id")
                .param("id", ky).query(String.class).single();
        assertThat(goi(http.get().uri("/api/v1/contests/{s}", slug).header(
                HttpHeaders.AUTHORIZATION, bearer(USER_ID, "ke-khac", Role.SETTER)))
                .getBody()).containsEntry("duocSoan", false);
        assertThat(goi(http.get().uri("/api/v1/contests/{s}", slug).header(
                HttpHeaders.AUTHORIZATION, bearer(SETTER_ID, "setter", Role.SETTER)))
                .getBody()).containsEntry("duocSoan", true);
    }

    // ---- sân -----------------------------------------------------------------------------

    /** Kỳ thi chưa mở — chỉ lúc ấy mới gắn/gỡ được đề, nên chốt sở hữu là chốt duy nhất đo. */
    private long kyThiCua(long chu) {
        return contests.tao(new ContestRepository.ContestMoi(
                "so-huu-" + System.nanoTime(), "Kỳ thi của " + chu, "ICPC",
                MOC.plus(Duration.ofHours(1)), MOC.plus(Duration.ofHours(4)), null,
                20, true, true, chu));
    }

    private long deCongKhaiCua(long chu) {
        return jdbc.sql("""
                INSERT INTO problems (code, title, statement_md, statement_hash, time_limit_ms,
                                      memory_limit_kb, owner_id, status, published_at)
                VALUES (:code, 'Đề riêng', 'x', :hash, 1000, 262144, :chu, 'PUBLISHED', now())
                RETURNING id
                """).param("code", "DE-CUA-" + chu + "-" + System.nanoTime())
                .param("hash", "d".repeat(64)).param("chu", chu)
                .query(Long.class).single();
    }

    /** problemId → "nhãn:điểm", để một ca đổi nhãn thất bại cũng lộ ra được. */
    private Map<Long, String> deTrongKyThi(long ky) {
        var ds = new java.util.LinkedHashMap<Long, String>();
        jdbc.sql("SELECT problem_id, label, points FROM contest_problems WHERE contest_id = :k")
                .param("k", ky)
                .query((rs, i) -> Map.entry(rs.getLong(1), rs.getString(2) + ":" + rs.getInt(3)))
                .list()
                .forEach(e -> ds.put(e.getKey(), e.getValue()));
        return ds;
    }

    private int soDe() {
        return jdbc.sql("SELECT count(*) FROM problems").query(Integer.class).single();
    }
}
