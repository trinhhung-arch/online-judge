package dev.oj.platform.jobs;

public enum JobType {

    REJUDGE,

    TESTDATA_IMPORT,

    LEADERBOARD_REBUILD,

    STANDINGS_DRIFT_CHECK,

    HOST_BENCHMARK;

    public static JobType fromCode(String code) {
        for (JobType t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Loại job không hợp lệ: " + code);
    }
}
