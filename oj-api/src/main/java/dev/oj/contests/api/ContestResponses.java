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
                          boolean daDangKy, String trangThai, List<De> cacDe) {

        /**
         * ★ {@code trangThai} suy ở SERVER, và nó là thứ làm cho {@code cacDe} đọc được.
         *
         * <p>{@code cacDe} rỗng ở <b>hai</b> nghĩa khác hẳn nhau: "chưa tới giờ, chưa cho
         * xem" và "kỳ thi này thật sự chưa có đề nào". Javadoc của
         * {@code GetContestUseCase.ChiTiet} đã ghi luật — phân biệt bằng thời gian, không
         * bằng độ dài danh sách — nhưng nếu response không mang trạng thái thì client phải
         * tự so {@code startsAt} với đồng hồ của <i>trình duyệt</i>.
         *
         * <p>Đồng hồ ấy lệch. Và nó lệch nhiều nhất ở đúng chỗ người ta để ý nhất: phút bắt
         * đầu kỳ thi. Một trang hiện "kỳ thi chưa bắt đầu" trong khi server đã mở là một
         * khiếu nại không ai giải quyết được, vì hai bên đang nhìn hai đồng hồ.
         */
        public static ChiTiet tu(GetContestUseCase.ChiTiet ct, java.time.Instant bayGio) {
            Contest c = ct.contest();
            return new ChiTiet(c.id(), c.slug(), c.title(), c.format().code(),
                    c.startsAt(), c.endsAt(), c.freezeAt(), c.daCongBo(),
                    c.registrationRequired(), ct.daDangKy(),
                    ListContestsUseCase.TrangThai.cua(c, bayGio).name(),
                    ct.cacDe().stream().map(De::tu).toList());
        }
    }

    /**
     * Một dòng của trang danh sách — Bước G4.
     *
     * <p><b>Cố ý không có {@code cacDe}.</b> Nếu record này mang danh sách đề thì trang danh
     * sách lộ đề của mọi kỳ thi chưa mở, qua đúng cái endpoint dùng để tìm kỳ thi. Xem
     * javadoc {@code ListContestsUseCase}.
     */
    public record TomTat(long id, String slug, String title, String format,
                         Instant startsAt, Instant endsAt,
                         boolean registrationRequired, String trangThai) {

        public static TomTat tu(ListContestsUseCase.TomTat t) {
            return new TomTat(t.id(), t.slug(), t.title(), t.format(),
                    t.startsAt(), t.endsAt(), t.registrationRequired(),
                    t.trangThai().name());
        }
    }

    /**
     * @param soanRieng V10 — đề sinh ra cho kỳ thi này. Trang kỳ thi dùng nó để nói rõ đề nào
     *                  là của riêng kỳ thi và đề nào mượn từ kho chung; đề mượn thì sửa nó là
     *                  sửa thứ người khác đang luyện tập
     */
    public record De(long problemId, String code, String label, int ordinal, int points,
                     boolean soanRieng) {

        static De tu(ContestRepository.DeCuaContest d) {
            return new De(d.problemId(), d.code(), d.label(), d.ordinal(), d.points(),
                    d.soanRieng());
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
