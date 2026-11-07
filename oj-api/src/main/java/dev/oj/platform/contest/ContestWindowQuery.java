package dev.oj.platform.contest;

import java.util.List;
import java.util.OptionalLong;

public interface ContestWindowQuery {

    OptionalLong contestDangChayChuaDe(long problemId);

    default boolean deDangTrongContestDangChay(long problemId) {
        return contestDangChayChuaDe(problemId).isPresent();
    }

    boolean coKyThiDangChay();

    boolean deBiKhoaBoiLichThi(long problemId, Long userId, boolean laNguoiRaDe);

    List<Long> deBiKhoaChoNguoiXem(Long userId);

    boolean deNamTrongKyThiNaoDo(long problemId);
}
