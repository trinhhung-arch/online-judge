package dev.oj.contests.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StandingsReader {

    List<Dong> top(long contestId, int n, boolean dongBang);

    Optional<Dong> cuaNguoi(long contestId, long userId, boolean dongBang);

    Optional<Integer> hang(long contestId, long userId, boolean dongBang);

    List<DongCoHang> quanhNguoi(long contestId, long userId, int k, boolean dongBang);

    record DongCoHang(Dong dong, int hang) {
    }

    record Dong(long userId, String handle, String displayName,
                int tongDiem, int penaltyGiay, int soBaiDat,
                Instant lanGhiDiemCuoi, int soBaiChoSauFreeze) {
    }
}
