package dev.oj.contract;

import java.math.BigDecimal;
import java.time.Instant;

public record HostBenchmarkDto(
        String hostName,
        String arch,
        Instant measuredAt,
        long cpuTimeMs,
        BigDecimal hostFactor,
        boolean calibrated,
        BigDecimal driftPct) {

    public static final double DRIFT_ALERT_PCT = 8.0;

    public HostBenchmarkDto {
        ContractChecks.requireText(hostName, "hostName");
        ContractChecks.requireText(arch, "arch");
        if (!"arm64".equals(arch) && !"amd64".equals(arch)) {
            throw new IllegalArgumentException(
                    "arch phải là arm64 hoặc amd64 (khớp CHECK trên judge_hosts): " + arch);
        }
        if (measuredAt == null) {
            throw new NullPointerException("measuredAt");
        }
        ContractChecks.requirePositive(cpuTimeMs, "cpuTimeMs");
        if (hostFactor == null || hostFactor.signum() <= 0) {
            throw new IllegalArgumentException("hostFactor phải dương, nhận: " + hostFactor);
        }
    }

    public boolean driftIsAlarming() {
        return driftPct != null && driftPct.abs().doubleValue() > DRIFT_ALERT_PCT;
    }

    public String note() {
        return "tải chuẩn " + cpuTimeMs + "ms CPU trên " + arch
                + (calibrated ? "" : " (CHƯA hiệu chuẩn — host_factor là hằng số tĩnh)");
    }
}
