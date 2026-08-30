package dev.oj.contests.infrastructure;

import dev.oj.contests.application.port.StandingsReader.Dong;
import org.springframework.data.redis.core.ZSetOperations;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Khoá Redis và phép chuyển dòng bảng xếp hạng thành phần tử {@code ZSET}.
 *
 * <h2>Vì sao ở một file riêng chứ không nằm trong {@code RedisStandingsCache}</h2>
 * Vì hai lớp cần chúng: cache thì <i>đọc và nạp</i>, còn {@code RedisStandingsPublisher} thì
 * <i>xoá</i>. Hai lớp tự dựng tên khoá là hai chỗ có thể lệch nhau — và triệu chứng của việc
 * lệch là <b>cache không bao giờ bị xoá</b>, tức là bảng xếp hạng đứng im ở bản cũ trong suốt
 * TTL. Không có lỗi nào được ném, không có dòng log nào.
 *
 * <h2>★ Điểm ZSET là VỊ TRÍ, không phải một phép gói điểm và penalty</h2>
 * Bản đầu của lớp này gói {@code (điểm, penalty, thời điểm)} vào một {@code double} để ZSET
 * tự sắp — kèm ba trần giá trị và một nhánh "vượt trần thì bỏ cache". Nó chạy được, nhưng nó
 * <b>thừa</b>: cache chỉ chứa top N, và top N <i>đã được Postgres sắp đúng</i> trước khi tới
 * đây. Điểm ZSET chỉ cần giữ nguyên thứ tự ấy.
 *
 * <p>Nên điểm là {@code soDong - viTri}. Không trần giá trị, không nhánh đặc biệt, không có
 * chỗ nào để hai người khác thứ hạng thành đồng hạng vì tràn số. Thứ tự trong cache trùng
 * thứ tự của SQL <b>theo định nghĩa</b>, không phải nhờ một phép mã hoá phải chứng minh.
 *
 * <p>Ghi lại vì đây là một cái bẫy dễ rơi vào: phép gói kia trông thông minh hơn, và nó giải
 * một bài toán mà thiết kế này không có.
 */
final class StandingsKeys {

    private static final String TIEN_TO = "oj:standings:";
    private static final String HAU_TO_THAT = ":live";
    private static final String HAU_TO_DONG_BANG = ":frozen";

    private StandingsKeys() {
    }

    static String zset(long contestId, boolean dongBang) {
        return TIEN_TO + contestId + (dongBang ? HAU_TO_DONG_BANG : HAU_TO_THAT);
    }

    /**
     * Chuyển các dòng <b>đã sắp đúng thứ tự</b> thành phần tử ZSET.
     *
     * <p>{@code ZREVRANGE} đọc theo điểm giảm dần, nên dòng đầu tiên phải có điểm cao nhất.
     */
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
