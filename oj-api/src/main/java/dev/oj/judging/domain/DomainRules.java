package dev.oj.judging.domain;

import dev.oj.contract.JudgeJobDto;
import dev.oj.contract.TestcaseMetaDto;

public final class DomainRules {

    public static final int PRIORITY_LIVE = 0;

    public static final int PRIORITY_REJUDGE = 10;

    public static final int ATTEMPT_NONE = 0;

    public static final int FIRST_ATTEMPT = 1;

    public static final int MAX_SOURCE_BYTES = JudgeJobDto.MAX_SOURCE_BYTES;

    public static final int MAX_TEST_ORDINAL = TestcaseMetaDto.MAX_ORDINAL;

    private DomainRules() {
    }

}
