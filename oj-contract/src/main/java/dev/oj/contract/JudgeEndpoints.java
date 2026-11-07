package dev.oj.contract;

public final class JudgeEndpoints {

    public static final String BASE = "/internal/judge";

    public static final String CLAIM = BASE + "/claim";

    public static final String RESULT = BASE + "/result";

    public static final String PROGRESS = BASE + "/progress";

    public static final String BENCHMARK = BASE + "/benchmark";

    public static final String TESTDATA = BASE + "/testdata";

    public static final String SECRET_HEADER = "X-Internal-Secret";

    private JudgeEndpoints() {
    }
}
