package dev.oj.judging.application.port;

import dev.oj.contract.HostBenchmarkDto;

/**
 * Ghi lịch sử hiệu chuẩn máy chấm — bảng {@code judge_hosts} và {@code host_benchmarks}.
 *
 * <p>Đây là port <b>duy nhất</b> của module {@code judging} không nằm trên đường
 * {@code nộp bài → verdict}. Nó tồn tại vì worker không có {@code DataSource} (bất biến #3):
 * phép đo tốc độ do worker thực hiện, nhưng chỗ duy nhất giữ được nó là Postgres, và đường
 * giữa hai chỗ đó phải đi qua API.
 */
public interface JudgeHostRepository {

    /**
     * Ghi một phép đo và cập nhật {@code judge_hosts.host_factor} + {@code last_seen_at}.
     *
     * @return {@code false} nếu {@code hostName} chưa có trong {@code judge_hosts} — <b>không
     *         ném</b>. Một máy chấm chưa đăng ký vẫn chấm bài được (xem
     *         {@code JdbcJudgeRunRepository}: {@code host_id} cho phép NULL), nên nó cũng
     *         không được phép làm hỏng một request chỉ vì chưa ai thêm nó vào bảng
     */
    boolean recordBenchmark(HostBenchmarkDto benchmark);
}
