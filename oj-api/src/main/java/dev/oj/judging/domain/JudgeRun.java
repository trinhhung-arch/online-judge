package dev.oj.judging.domain;

import dev.oj.contract.JudgeResultDto;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public record JudgeRun(
        long submissionId,
        int attempt,
        String hostName,
        BigDecimal hostFactor,
        int languageId,
        int testdataVersion,
        JudgeOutcome outcome,
        int testsRun,
        String compileLog,
        String isolateStatus,
        String traceId,
        Instant startedAt,
        Instant finishedAt) {

    private static final String TRUNCATION_MARK = "\n… [đã cắt bớt]";

    public JudgeRun {
        if (submissionId <= 0 || languageId <= 0) {
            throw new IllegalArgumentException("submissionId và languageId phải dương");
        }
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalArgumentException("hostName không được rỗng — số đo thời gian "
                    + "không kèm tên máy là số đo vô nghĩa (nfrplan.md 9.1)");
        }
        if (attempt < DomainRules.FIRST_ATTEMPT) {
            throw new IllegalArgumentException(
                    "attempt phải >= " + DomainRules.FIRST_ATTEMPT + ", nhận được: " + attempt);
        }
        if (testdataVersion < 1) {
            throw new IllegalArgumentException("testdataVersion phải >= 1: " + testdataVersion);
        }
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương: " + hostFactor);
        }
        if (outcome == null) {
            throw new NullPointerException("outcome");
        }
        if (testsRun < 0) {
            throw new IllegalArgumentException("testsRun không âm: " + testsRun);
        }
        if (finishedAt == null) {
            throw new NullPointerException("finishedAt");
        }
        compileLog = truncate(compileLog, JudgeResultDto.MAX_COMPILE_LOG_BYTES);
        isolateStatus = truncate(isolateStatus, JudgeResultDto.MAX_ISOLATE_STATUS_BYTES);
    }

    public boolean isFirstAttempt() {
        return attempt == DomainRules.FIRST_ATTEMPT;
    }

    private static String truncate(String value, int maxBytes) {
        if (value == null) {
            return null;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        int cut = maxBytes - TRUNCATION_MARK.getBytes(StandardCharsets.UTF_8).length;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        return cut <= 0 ? "" : new String(bytes, 0, cut, StandardCharsets.UTF_8) + TRUNCATION_MARK;
    }

}
