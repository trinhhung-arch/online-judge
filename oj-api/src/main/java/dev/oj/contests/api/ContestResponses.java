package dev.oj.contests.api;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetContestUseCase;
import dev.oj.contests.application.usecase.ListContestsUseCase;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.domain.Contest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ContestResponses {

    private ContestResponses() {
    }

    public record ChiTiet(long id, String slug, String title, String format,
                          Instant startsAt, Instant endsAt, Instant freezeAt,
                          boolean daCongBo, boolean registrationRequired,
                          boolean daDangKy, String trangThai, List<De> cacDe,
                          boolean duocSoan) {

        public static ChiTiet tu(GetContestUseCase.ChiTiet ct, java.time.Instant bayGio) {
            Contest c = ct.contest();
            return new ChiTiet(c.id(), c.slug(), c.title(), c.format().code(),
                    c.startsAt(), c.endsAt(), c.freezeAt(), c.daCongBo(),
                    c.registrationRequired(), ct.daDangKy(),
                    ListContestsUseCase.TrangThai.cua(c, bayGio).name(),
                    ct.cacDe().stream().map(De::tu).toList(),
                    ct.duocSoan());
        }
    }

    public record TomTat(long id, String slug, String title, String format,
                         Instant startsAt, Instant endsAt,
                         boolean registrationRequired, String trangThai) {

        public static TomTat tu(ListContestsUseCase.TomTat t) {
            return new TomTat(t.id(), t.slug(), t.title(), t.format(),
                    t.startsAt(), t.endsAt(), t.registrationRequired(),
                    t.trangThai().name());
        }
    }

    public record De(long problemId, String code, String label, int points,
                     boolean soanRieng) {

        static De tu(ContestRepository.DeCuaContest d) {
            return new De(d.problemId(), d.code(), d.label(), d.points(), d.soanRieng());
        }
    }

    public record BangXepHang(long contestId, String format, boolean dongBang,
                              List<Dong> top, Dong cuaToi, Integer hangCuaToi,
                              List<Dong> quanhToi) {

        public static BangXepHang tu(GetStandingsUseCase.BangXepHang b) {
            return new BangXepHang(
                    b.contest().id(), b.contest().format().code(), b.dongBang(),
                    xepHang(b.top()),
                    b.cuaToi() == null ? null : Dong.tu(b.cuaToi(), b.hangCuaToi()),
                    b.hangCuaToi(),
                    b.quanhToi().stream().map(d -> Dong.tu(d.dong(), d.hang())).toList());
        }

        private static List<Dong> xepHang(List<StandingsReader.Dong> cac) {
            List<Dong> ketQua = new ArrayList<>(cac.size());
            int hang = 0;
            StandingsReader.Dong truoc = null;
            for (int i = 0; i < cac.size(); i++) {
                StandingsReader.Dong d = cac.get(i);
                if (truoc == null || !bangNhau(truoc, d)) {
                    hang = i + 1;
                }
                ketQua.add(Dong.tu(d, hang));
                truoc = d;
            }
            return ketQua;
        }

        private static boolean bangNhau(StandingsReader.Dong a, StandingsReader.Dong b) {
            return a.tongDiem() == b.tongDiem()
                    && a.penaltyGiay() == b.penaltyGiay()
                    && java.util.Objects.equals(a.lanGhiDiemCuoi(), b.lanGhiDiemCuoi());
        }
    }

    public record Dong(Integer hang, long userId, String handle, String displayName,
                       int tongDiem, int penaltyGiay, int soBaiDat, int soBaiChoSauFreeze) {

        static Dong tu(StandingsReader.Dong d, Integer hang) {
            return new Dong(hang, d.userId(), d.handle(), d.displayName(),
                    d.tongDiem(), d.penaltyGiay(), d.soBaiDat(), d.soBaiChoSauFreeze());
        }
    }
}
