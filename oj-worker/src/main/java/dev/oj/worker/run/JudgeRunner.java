package dev.oj.worker.run;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;

public interface JudgeRunner {

    JudgeResultDto run(JudgeJobDto job);
}
