package dev.oj.problems.domain;

import java.util.regex.Pattern;

public final class TestdataLimits {

    public static final long MAX_ZIP_BYTES = 200L * 1024 * 1024;

    public static final long MAX_GIAI_NEN_BYTES = 2L * 1024 * 1024 * 1024;

    public static final int MAX_TI_LE_NEN = 100;

    public static final int MAX_TEST = 1000;

    public static final int MAX_SAMPLE_BYTES = 65_536;

    public static final int MAX_MOT_FILE_BYTES = 64 * 1024 * 1024;

    public static final Pattern TEN_FILE_TEST =
            Pattern.compile("^tests/[A-Za-z0-9_.-]{1,64}\\.(in|out)$");

    public static final String MANIFEST = "problem.yaml";

    private TestdataLimits() {
    }
}
