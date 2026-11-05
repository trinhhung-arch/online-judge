package dev.oj.judging.application.port;

import dev.oj.contract.HostBenchmarkDto;

public interface JudgeHostRepository {

    boolean recordBenchmark(HostBenchmarkDto benchmark);
}
