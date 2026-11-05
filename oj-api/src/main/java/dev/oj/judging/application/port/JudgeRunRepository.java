package dev.oj.judging.application.port;

import dev.oj.judging.domain.JudgeRun;

public interface JudgeRunRepository {

    boolean insertIfAbsent(JudgeRun run);

    void insertSubtaskResults(long submissionId, int attempt,
                              java.util.List<dev.oj.contract.SubtaskResultDto> subtasks);
}
