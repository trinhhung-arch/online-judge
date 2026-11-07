package dev.oj.platform.metrics;

public final class OjMetrics {

    public static final String QUEUE_WAITING = "oj.queue.waiting";

    public static final String QUEUE_JUDGING = "oj.queue.judging";

    public static final String QUEUE_WAIT_MS = "oj.queue.wait.ms";

    public static final String QUEUE_REJUDGE_WAITING = "oj.queue.rejudge.waiting";

    public static final String WORKERS_LIVE = "oj.workers.live";

    public static final String JUDGE_FINISHED = "oj.judge.finished";

    public static final String TAG_VERDICT = "verdict";

    public static final String STANDINGS_DRIFT = "oj.standings.drift";

    private OjMetrics() {
    }
}
