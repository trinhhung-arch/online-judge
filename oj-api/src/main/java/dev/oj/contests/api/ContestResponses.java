package dev.oj.contests.api;

import dev.oj.contests.application.port.ContestRepository;
import dev.oj.contests.application.port.StandingsReader;
import dev.oj.contests.application.usecase.GetContestUseCase;
import dev.oj.contests.application.usecase.GetStandingsUseCase;
import dev.oj.contests.domain.Contest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO của module {@code contests}. Entity không bao giờ rời {@code domain}
 * ({@code CLAUDE.md} mục 7).
 */
public final class ContestResponses {

    private ContestResponses() {
    }

    /**
     * @param cacDe rỗng khi kỳ thi chưa mở. Cùng lý do ở {@code GetContestUseCase}: danh sách
     *              mã đề đã là thông tin
     */
    public record ChiTiet(long id, String slug, String title, String format,
                          Instant startsAt, Instant endsAt, Instant freezeAt,
                          boolean daCongBo, boolean registrationRequired,
                          boolean daDangKy, List<De> cacDe) {

        public static ChiTiet tu(GetContestUseCase.ChiTiet ct) {
            Contest c = ct.contest();
            return new ChiTiet(c.id(), c.slug(), c.title(), c.format().code(),
                    c.startsAt(), c.endsAt(), c.freezeAt(), c.daCongBo(),
                    c.registrationRequired(), ct.daDangKy(),
                    ct.cacDe().stream().map(De::tu).toList());
        }
    }

    public record De(long problemId, String label, int ordinal, int points) {

        static De tu(ContestRepository.DeCuaContest d) {
            return new De(d.problemId(), d.label(), d.ordinal(), d.points());
        }
    }

    /**
     * @param dongBang bảng đang hiện <b>bản chụp</b>. UI phải nói rõ — một bảng đóng băng mà
     *                 trông như bảng thật là một lời nói dối, và thí sinh sẽ tính chiến thuật
     *                 dựa trên nó
     */
    public record BangXepHang(long contestId, String format, boolean dongBang,
                              List<Dong> top, Dong cuaToi, Integer hangCuaToi) {

        public static BangXepHang tu(GetStandingsUseCase.BangXepHang b) {
            return new BangXepHang(
                    b.contest().id(), b.contest().format().code(), b.dongBang(),
                    xepHang(b.top()),
                    b.cuaToi() == null ? null : Dong.tu(b.cuaToi(), b.hangCuaToi()),
                    b.hangCuaToi());
        }

        /**
         * ★ Thứ hạng có ĐỒNG HẠNG, tính như {@code rank()} của Postgres: hai người bằng nhau
         * cùng hạng 1, người kế tiếp là hạng 3.
         *
         * <p>Lấy {@code index + 1} sẽ ra 1, 2, 3 — và khác đúng ở chỗ người ta để ý nhất.
         * Tính ở đây thay vì hỏi database thêm một lượt cho mỗi dòng: danh sách đã được sắp
         * đúng thứ tự, nên đồng hạng là một phép so với dòng liền trước.
         */
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

    /**
     * @param soBaiChoSauFreeze ô "?" kiểu ICPC — số bài nộp sau giờ đóng băng mà kết quả chưa
     *                          được công bố. Luôn 0 khi bảng không đóng băng
     */
    public record Dong(Integer hang, long userId, String handle, String displayName,
                       int tongDiem, int penaltyGiay, int soBaiDat, int soBaiChoSauFreeze) {

        static Dong tu(StandingsReader.Dong d, Integer hang) {
            return new Dong(hang, d.userId(), d.handle(), d.displayName(),
                    d.tongDiem(), d.penaltyGiay(), d.soBaiDat(), d.soBaiChoSauFreeze());
        }
    }
}
