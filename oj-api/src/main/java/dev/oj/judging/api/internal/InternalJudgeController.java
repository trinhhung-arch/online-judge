package dev.oj.judging.api.internal;

import dev.oj.contract.ClaimRequestDto;
import dev.oj.contract.HostBenchmarkDto;
import dev.oj.contract.JudgeProgressDto;
import dev.oj.contract.JudgeEndpoints;
import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.JudgeResultDto;
import dev.oj.judging.application.usecase.ClaimJudgeJobUseCase;
import dev.oj.judging.application.usecase.RecordHostBenchmarkUseCase;
import dev.oj.judging.application.usecase.RecordJudgeProgressUseCase;
import dev.oj.judging.application.usecase.RecordJudgeResultUseCase;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(JudgeEndpoints.BASE)
public class InternalJudgeController {

    private final ClaimJudgeJobUseCase claimJudgeJob;
    private final RecordJudgeResultUseCase recordJudgeResult;
    private final RecordHostBenchmarkUseCase recordHostBenchmark;
    private final RecordJudgeProgressUseCase recordJudgeProgress;

    public InternalJudgeController(ClaimJudgeJobUseCase claimJudgeJob,
                                   RecordJudgeResultUseCase recordJudgeResult,
                                   RecordHostBenchmarkUseCase recordHostBenchmark,
                                   RecordJudgeProgressUseCase recordJudgeProgress) {
        this.claimJudgeJob = claimJudgeJob;
        this.recordJudgeResult = recordJudgeResult;
        this.recordHostBenchmark = recordHostBenchmark;
        this.recordJudgeProgress = recordJudgeProgress;
    }

    @PostMapping("/claim")
    public ResponseEntity<JudgeJobDto> claim(@RequestBody ClaimRequestDto request) {
        return claimJudgeJob.claim(request)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/result")
    public ResponseEntity<Void> result(@RequestBody JudgeResultDto result) {
        recordJudgeResult.record(result);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/progress")
    public ResponseEntity<Void> progress(@RequestBody JudgeProgressDto progress) {
        recordJudgeProgress.record(progress);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/benchmark")
    public ResponseEntity<Void> benchmark(@RequestBody HostBenchmarkDto benchmark) {
        recordHostBenchmark.record(benchmark);
        return ResponseEntity.noContent().build();
    }

}
