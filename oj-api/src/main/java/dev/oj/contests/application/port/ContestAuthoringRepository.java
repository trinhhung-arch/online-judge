package dev.oj.contests.application.port;

import dev.oj.contests.domain.Contest;

import java.util.Optional;

public interface ContestAuthoringRepository {

    Optional<Contest> timDeSoan(long contestId, long nguoiGoi, boolean laAdmin);

    void themDe(long contestId, long problemId, String label, int points,
                long nguoiGoi, boolean laAdmin);

    void themDeSoanRieng(long contestId, long problemId, String label, int points,
                         long nguoiGoi, boolean laAdmin);

    boolean goDe(long contestId, long problemId, long nguoiGoi, boolean laAdmin);
}
