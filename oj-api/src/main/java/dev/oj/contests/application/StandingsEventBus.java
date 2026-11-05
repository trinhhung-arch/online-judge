package dev.oj.contests.application;

public interface StandingsEventBus {

    void bangDaDoi(long contestId);

    AutoCloseable subscribe(long contestId, Runnable khiDoi);
}
