package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsReader.Dong;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class StandingsKeys {

    private static final String TIEN_TO = "oj:standings:";
    private static final String HAU_TO_THAT = ":live";
    private static final String HAU_TO_DONG_BANG = ":frozen";

    private StandingsKeys() {
    }

    static String zset(long contestId, boolean dongBang) {
        return TIEN_TO + contestId + (dongBang ? HAU_TO_DONG_BANG : HAU_TO_THAT);
    }

    static Set<ZSetOperations.TypedTuple<String>> dongThanhTuple(List<Dong> cac,
                                                                 ObjectMapper json) {
        Set<ZSetOperations.TypedTuple<String>> bo = new LinkedHashSet<>();
        for (int i = 0; i < cac.size(); i++) {
            bo.add(ZSetOperations.TypedTuple.of(
                    json.writeValueAsString(cac.get(i)), (double) (cac.size() - i)));
        }
        return bo;
    }
}
