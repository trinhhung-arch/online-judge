package dev.oj.contests.infrastructure;

final class StandingsKeys {

    private static final String TIEN_TO = "oj:standings:";
    private static final String HAU_TO_THAT = ":live";
    private static final String HAU_TO_DONG_BANG = ":frozen";
    private static final String HAU_TO_PHIEN_BAN = ":ver";

    private StandingsKeys() {
    }

    static String zset(long contestId, boolean dongBang) {
        return TIEN_TO + contestId + (dongBang ? HAU_TO_DONG_BANG : HAU_TO_THAT);
    }

    static String phienBan(long contestId) {
        return TIEN_TO + contestId + HAU_TO_PHIEN_BAN;
    }
}
