package dev.oj.contests.application.port;

import dev.oj.contests.domain.Contest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ContestRepository {

    Optional<Contest> timTheoId(long contestId);

    Optional<Contest> timTheoSlug(String slug);

    long tao(ContestMoi contest);

    List<DeCuaContest> deCua(long contestId);

    boolean daDangKy(long contestId, long userId);

    void dangKy(long contestId, long userId, Instant luc);

    List<Contest> danhSach(Long sauId, int gioiHan);

    List<Contest> canCapNhat(Instant bayGio);

    void chotBangXepHang(long contestId, Instant luc);

    List<Contest> canDongBang(Instant bayGio);

    void congBo(long contestId, Instant luc);

    record ContestMoi(String slug, String title, String format,
                      Instant startsAt, Instant endsAt, Instant freezeAt,
                      int penaltyMinutes, boolean registrationRequired,
                      boolean revealAfterEnd, long createdBy) {
    }

    record DeCuaContest(long problemId, String code, String label, int points,
                        boolean soanRieng) {
    }
}
