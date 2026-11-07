package dev.oj.contests.application.usecase;

import dev.oj.contests.domain.ContestsException;
import dev.oj.platform.config.AppPropertiesGia;
import dev.oj.platform.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaoKyThiKiemTraTest {

    private static final Instant BAY_GIO = Instant.parse("2026-11-07T10:00:00Z");
    private static final Instant BAT_DAU = BAY_GIO.plus(Duration.ofDays(1));
    private static final Instant KET_THUC = BAT_DAU.plus(Duration.ofHours(5));
    private static final int PHUT_PHAT_TOI_DA =
            AppPropertiesGia.macDinh().contest().penaltyMinutesMax();

    private static AuthorContestUseCase.Lenh lenh(String slug, String title, Instant batDau,
                                                  Instant ketThuc, Instant dongBang) {
        return new AuthorContestUseCase.Lenh(slug, title, "ICPC", batDau, ketThuc, dongBang,
                20, true, true);
    }

    private static AuthorContestUseCase.Lenh voiPhutPhat(int phut) {
        return new AuthorContestUseCase.Lenh("thi-thu", "Thi thử", "ICPC", BAT_DAU, KET_THUC,
                null, phut, true, true);
    }

    private static AuthorContestUseCase.Lenh voiTheThuc(String theThuc) {
        return new AuthorContestUseCase.Lenh("thi-thu", "Thi thử", theThuc, BAT_DAU, KET_THUC,
                null, 20, true, true);
    }

    private static AuthorContestUseCase.Lenh voiSlug(String slug) {
        return lenh(slug, "Thi thử", BAT_DAU, KET_THUC, null);
    }

    private static AuthorContestUseCase.Lenh voiTieuDe(String title) {
        return lenh("thi-thu", title, BAT_DAU, KET_THUC, null);
    }

    private static AuthorContestUseCase.Lenh voiGio(Instant batDau, Instant ketThuc,
                                                    Instant dongBang) {
        return lenh("thi-thu", "Thi thử", batDau, ketThuc, dongBang);
    }

    private static void hopLe(AuthorContestUseCase.Lenh lenh) {
        assertThatCode(() -> lenh.kiemTra(BAY_GIO, PHUT_PHAT_TOI_DA)).as(lenh.toString())
                .doesNotThrowAnyException();
    }

    private static void loi(AuthorContestUseCase.Lenh lenh, String ma) {
        assertThatThrownBy(() -> lenh.kiemTra(BAY_GIO, PHUT_PHAT_TOI_DA))
                .as(lenh.toString())
                .isInstanceOf(ContestsException.class)
                .hasFieldOrPropertyWithValue("kind", DomainException.Kind.INVALID)
                .hasFieldOrPropertyWithValue("code", ma);
    }

    @Test
    @DisplayName("★ AT-02 · slug dài 1 → 400; dài 2 và 64 → được; dài 65, có chữ hoa, bắt đầu bằng '-' → 400")
    void at02_slug() {
        loi(voiSlug("a"), "contest.slug_khong_hop_le");
        hopLe(voiSlug("ab"));
        hopLe(voiSlug("a".repeat(64)));
        loi(voiSlug("a".repeat(65)), "contest.slug_khong_hop_le");
        loi(voiSlug("Thi-thu"), "contest.slug_khong_hop_le");
        loi(voiSlug("-thi-thu"), "contest.slug_khong_hop_le");
    }

    @Test
    @DisplayName("★ AT-04 · tiêu đề rỗng, toàn khoảng trắng → 400; 200 ký tự được; 201 ký tự → 400")
    void at04_tieu_de() {
        loi(voiTieuDe(""), "contest.tieu_de_khong_hop_le");
        loi(voiTieuDe("   "), "contest.tieu_de_khong_hop_le");
        hopLe(voiTieuDe("t".repeat(200)));
        loi(voiTieuDe("t".repeat(201)), "contest.tieu_de_khong_hop_le");
    }

    @Test
    @DisplayName("★ AT-05 · thể thức ICPC và IOI được; CF, chữ thường icpc, rỗng và null → 400 contest.the_thuc_khong_hop_le")
    void at05_the_thuc() {
        hopLe(voiTheThuc("ICPC"));
        hopLe(voiTheThuc("IOI"));
        for (String sai : Arrays.asList("CF", "icpc", "", null)) {
            loi(voiTheThuc(sai), "contest.the_thuc_khong_hop_le");
        }
    }

    @Test
    @DisplayName("★ AT-06 · ends = starts → 400; freeze = starts → 400, = ends được, > ends → 400; starts = bây giờ → 400, bây giờ + 1s được")
    void at06_khung_gio() {
        loi(voiGio(BAT_DAU, BAT_DAU, null), "contest.khung_gio_khong_hop_le");
        loi(voiGio(BAT_DAU, KET_THUC, BAT_DAU), "contest.gio_dong_bang_khong_hop_le");
        hopLe(voiGio(BAT_DAU, KET_THUC, KET_THUC));
        loi(voiGio(BAT_DAU, KET_THUC, KET_THUC.plusSeconds(1)),
                "contest.gio_dong_bang_khong_hop_le");
        loi(voiGio(BAY_GIO, KET_THUC, null), "contest.bat_dau_trong_qua_khu");
        hopLe(voiGio(BAY_GIO.plusSeconds(1), KET_THUC, null));
    }

    @Test
    @DisplayName("★ AT-07 · phút phạt −1 → 400; 0 và đúng trần được; trần + 1 và 40000 (tràn SMALLINT) → 400, không còn 500")
    void at07_phut_phat() {
        loi(voiPhutPhat(-1), "contest.penalty_am");
        hopLe(voiPhutPhat(0));
        hopLe(voiPhutPhat(PHUT_PHAT_TOI_DA));
        loi(voiPhutPhat(PHUT_PHAT_TOI_DA + 1), "contest.penalty_qua_lon");
        loi(voiPhutPhat(40_000), "contest.penalty_qua_lon");
    }
}
