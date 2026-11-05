package dev.oj.problems.application.port;

import dev.oj.problems.domain.JudgeSpec;

import java.util.Optional;

public interface JudgeSpecRepository {

    Optional<JudgeSpec> findJudgeSpec(long problemId, int testdataVersion);
}
