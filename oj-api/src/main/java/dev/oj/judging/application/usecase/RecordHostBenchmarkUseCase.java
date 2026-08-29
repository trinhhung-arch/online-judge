package dev.oj.judging.application.usecase;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.judging.application.port.JudgeHostRepository;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Ghi một phép đo tốc độ máy chấm — {@code POST /internal/judge/benchmark}.
 *
 * <h2>Vì sao use-case này KHÔNG giống mọi use-case khác của module</h2>
 * Nó là cái duy nhất không nằm trên đường {@code nộp bài → verdict}. Worker gọi 15 phút một
 * lần từ luồng lịch riêng, nên nó không tiêu một phần nào của ngân sách 2 giây
 * ({@code nfrplan.md} 2.1) — và cũng vì thế nó <b>không được phép</b> làm hỏng việc chấm bài:
 * mọi kết cục bất thường ở đây đều là log, không phải exception.
 *
 * <h2>Hai câu, một transaction</h2>
 * {@code UPDATE judge_hosts} rồi {@code INSERT host_benchmarks}. Tách ra thì có thể xảy ra
 * chuyện {@code host_factor} đã đổi mà không có dòng lịch sử nào giải thích vì sao — đúng thứ
 * bảng này sinh ra để tránh.
 *
 * <h2>Ghi MỌI phép đo, không chỉ khi vượt ngưỡng</h2>
 * 15 phút một lần là 96 dòng/máy/ngày, ~35 nghìn dòng một năm cho ba máy — không đáng kể trên
 * một bảng nguội ({@code postgres-design.md} mục 3). Đổi lại, câu hỏi sau mỗi kỳ thi
 * <i>"máy chấm hôm đó có chậm không"</i> trả lời được bằng dữ liệu chứ không bằng trí nhớ.
 * Chỉ ghi lúc vượt ngưỡng thì đúng lúc cần đối chiếu lại không có gì để đối chiếu.
 */
@InternalAccess("worker, qua POST /internal/judge/benchmark, 15 phút một lần.")
@Service
public class RecordHostBenchmarkUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordHostBenchmarkUseCase.class);

    private final JudgeHostRepository hosts;

    public RecordHostBenchmarkUseCase(JudgeHostRepository hosts) {
        this.hosts = hosts;
    }

    @JudgeTransactional
    public void record(HostBenchmarkDto benchmark) {
        if (benchmark.driftIsAlarming()) {
            // ERROR chứ không WARN: giữa một contest thì đây là thứ phải đánh thức người trực.
            log.error("⚠️ MÁY CHẤM '{}' ({}) ĐỔI TỐC ĐỘ {}% so với lần đo đầu — tải chuẩn {}ms "
                            + "CPU. Nếu đang có contest thì bài chấm lúc này KHÔNG cùng điều "
                            + "kiện với bài chấm lúc đầu giờ (nfrplan rủi ro #5: throttle nhiệt).",
                    benchmark.hostName(), benchmark.arch(), benchmark.driftPct(),
                    benchmark.cpuTimeMs());
        }

        if (!hosts.recordBenchmark(benchmark)) {
            // Máy chưa đăng ký vẫn chấm bài được (judge_runs.host_id cho phép NULL, S2), nên
            // đây là chuyện của người vận hành chứ không phải lỗi của request.
            log.warn("Máy chấm '{}' không có trong judge_hosts — bỏ qua phép đo. Thêm một dòng "
                            + "vào bảng đó nếu muốn giữ lịch sử hiệu chuẩn của nó.",
                    benchmark.hostName());
        }
    }
}
