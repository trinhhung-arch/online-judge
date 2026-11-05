package dev.oj.judging.application.usecase;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.judging.application.port.JudgeHostRepository;
import dev.oj.platform.config.JudgeTransactional;
import dev.oj.platform.security.InternalAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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
            log.error("⚠️ MÁY CHẤM '{}' ({}) ĐỔI TỐC ĐỘ {}% so với lần đo đầu — tải chuẩn {}ms "
                            + "CPU. Nếu đang có contest thì bài chấm lúc này KHÔNG cùng điều "
                            + "kiện với bài chấm lúc đầu giờ (nfrplan rủi ro #5: throttle nhiệt).",
                    benchmark.hostName(), benchmark.arch(), benchmark.driftPct(),
                    benchmark.cpuTimeMs());
        }

        if (!hosts.recordBenchmark(benchmark)) {
            log.warn("Máy chấm '{}' không có trong judge_hosts — bỏ qua phép đo. Thêm một dòng "
                            + "vào bảng đó nếu muốn giữ lịch sử hiệu chuẩn của nó.",
                    benchmark.hostName());
        }
    }
}
