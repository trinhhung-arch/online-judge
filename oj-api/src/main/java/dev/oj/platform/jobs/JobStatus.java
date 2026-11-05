package dev.oj.platform.jobs;

public enum JobStatus {

    PENDING,
    RUNNING,
    PAUSED,
    DONE,
    FAILED,
    CANCELLED;

    public boolean dangSong() {
        return this == PENDING || this == RUNNING || this == PAUSED;
    }

    public boolean daKetThuc() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }

    public boolean cothechay() {
        return this == PENDING || this == PAUSED;
    }

    public static JobStatus fromCode(String code) {
        for (JobStatus s : values()) {
            if (s.name().equals(code)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Trạng thái job không hợp lệ: " + code);
    }
}
