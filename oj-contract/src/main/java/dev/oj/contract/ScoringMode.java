package dev.oj.contract;

public enum ScoringMode {

    ALL_OR_NOTHING,

    SUBTASK;

    public boolean allowsEarlyExit() {
        return this == ALL_OR_NOTHING;
    }
}
