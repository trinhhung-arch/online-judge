package dev.oj.platform.jobs;

public interface JobHandler {

    JobType type();

    void chay(JobContext ctx);
}
