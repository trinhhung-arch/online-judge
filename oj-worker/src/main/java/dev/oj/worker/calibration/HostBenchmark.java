package dev.oj.worker.calibration;

import dev.oj.contract.HostBenchmarkDto;
import dev.oj.worker.client.JudgeApiClient;
import dev.oj.worker.config.WorkerProperties;
import dev.oj.worker.sandbox.CommandTemplate;
import dev.oj.worker.sandbox.IsolateBox;
import dev.oj.worker.sandbox.IsolateCommand;
import dev.oj.worker.sandbox.IsolateMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Đo tốc độ máy chấm lúc khởi động và mỗi 15 phút. Bước 2.9, {@code nfrplan.md} 9.1.
 *
 * <h2>Hai việc, và việc thứ hai mới là việc cứu một kỳ thi</h2>
 * <ol>
 *   <li><b>Hiệu chuẩn:</b> {@code host_factor = thời gian đo được / thời gian trên máy chấm
 *       chuẩn}. Đây là thứ làm cho một con số thời gian có nghĩa; thiếu nó thì "máy tao AC mà
 *       CI báo TLE" là chuyện chắc chắn xảy ra, vì máy dev x86 và host ARM lệch nhau 20-50%.</li>
 *   <li><b>Phát hiện throttle nhiệt:</b> so mỗi lần đo với <b>lần đo đầu tiên của chính máy
 *       này</b>. Đây là rủi ro #5 của {@code nfrplan} Phần 13: máy chạy 90 phút contest nóng
 *       dần và chậm dần, bài nộp phút thứ 85 bị TLE trong khi bài y hệt ở phút thứ 5 được AC.
 *       Không ai trong phòng thi nhận ra, và không có cách nào chứng minh sau đó.</li>
 * </ol>
 *
 * <h2>Vì sao mặc định KHÔNG đổi giới hạn chấm</h2>
 * {@code reference-cpu-ms: 0} nghĩa là chưa ai đo tải chuẩn trên máy chấm chuẩn — Mac M1 Max
 * chưa được deploy tới Bước 2.10. Để một hằng số chưa ai đo quyết định TLE của thí sinh thì
 * tệ hơn hẳn so với không hiệu chuẩn. Nhưng việc <b>đo</b> và <b>cảnh báo drift</b> thì chạy
 * ngay từ bây giờ, vì chúng không cần con số ấy.
 *
 * <h2>Box riêng, ngoài pool</h2>
 * Chạy trên {@code first-box-id + slots} nên nó không mượn slot của ai. Một bài benchmark 15
 * phút một lần mà cướp một trong sáu slot chấm là tự tạo ra đúng thứ nó đi tìm.
 *
 * <h2>⚠️ Chưa ghi vào bảng {@code host_benchmarks}</h2>
 * Bảng ấy có từ {@code V1}, nhưng worker không có {@code DataSource} (bất biến #3) và
 * {@code oj-contract} chưa có đường nào để gửi một phép đo về API. Thêm đường ấy là đổi hợp
 * đồng — việc phải hỏi người ({@code CLAUDE.md} mục 5.1). Tới lúc đó, lịch sử đo chỉ nằm
 * trong log, nghĩa là mất khi worker khởi động lại.
 */
@Component
public class HostBenchmark {

    private static final Logger log = LoggerFactory.getLogger(HostBenchmark.class);
    private static final String SOURCE = "Bench.cpp";

    private final WorkerProperties properties;
    private final JudgeApiClient api;
    private final int boxId;

    private volatile BigDecimal factor;
    private volatile long baselineCpuMs;

    /**
     * @param api có thể {@code null} trong test đơn vị: phép đo vẫn chạy và vẫn cảnh báo, chỉ
     *            không gửi đi đâu. Đó cũng đúng là hành vi mong muốn khi API đang xuống
     */
    public HostBenchmark(WorkerProperties properties, JudgeApiClient api) {
        this.properties = properties;
        this.api = api;
        this.boxId = properties.sandbox().firstBoxId() + properties.slots();
        this.factor = properties.hostFactor();
    }

    /**
     * Hệ số đang dùng để nhân vào giới hạn thời gian.
     *
     * <p>Trả về hệ số <b>đo được</b> khi đã hiệu chuẩn, và hệ số tĩnh trong cấu hình khi chưa.
     */
    public BigDecimal current() {
        return factor;
    }

    /** Lần đo gần nhất, mili giây CPU. {@code 0} nghĩa là chưa đo được lần nào. */
    public long lastCpuMs() {
        return baselineCpuMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void measureOnStartup() {
        measure();
    }

    @Scheduled(fixedDelayString = "${oj.worker.sandbox.benchmark.interval}",
            initialDelayString = "${oj.worker.sandbox.benchmark.interval}")
    public void measure() {
        // Bean này KHÔNG @ConditionalOnProperty: JobExecutor phụ thuộc nó, và một bean biến
        // mất theo cấu hình sẽ làm cả context không khởi động được ở nhánh sandbox tắt.
        // Chặn ở đây thì rõ ràng hơn và không có nhánh dây nào chỉ tồn tại trong một cấu hình.
        if (!properties.sandbox().enabled()) {
            return;
        }
        try {
            long cpuMs = median(runSamples());
            if (cpuMs <= 0) {
                return;
            }
            BigDecimal driftPct = reportDrift(cpuMs);
            if (properties.sandbox().benchmark().calibrated()) {
                factor = BigDecimal.valueOf(cpuMs)
                        .divide(BigDecimal.valueOf(properties.sandbox().benchmark().referenceCpuMs()),
                                3, RoundingMode.HALF_UP);
                log.info("Hiệu chuẩn máy '{}' ({}): {}ms CPU, host_factor = {}",
                        properties.hostName(), properties.arch(), cpuMs, factor);
            } else {
                log.info("Đo máy '{}' ({}): {}ms CPU. CHƯA hiệu chuẩn — đặt "
                                + "oj.worker.sandbox.benchmark.reference-cpu-ms bằng con số đo "
                                + "trên máy chấm chuẩn (Bước 2.10) thì host_factor mới có nghĩa. "
                                + "Đang dùng hệ số tĩnh {}.",
                        properties.hostName(), properties.arch(), cpuMs, factor);
            }
            publish(cpuMs, driftPct);
        } catch (RuntimeException e) {
            // Benchmark hỏng KHÔNG được làm worker dừng chấm: hệ số cũ vẫn dùng được, và một
            // hệ số hơi cũ tốt hơn hẳn một máy chấm không chạy.
            log.warn("Không đo được tốc độ máy, giữ host_factor = {}: {}", factor, e.toString());
        }
    }

    /**
     * So với <b>lần đo đầu tiên của chính máy này</b>, không phải với máy chấm chuẩn. Đó là
     * điều kiện để bắt được throttle nhiệt: máy ARM vốn chậm hơn máy chuẩn 30% vẫn bình
     * thường, nhưng chính nó chậm đi 10% so với nửa giờ trước thì không.
     */
    private BigDecimal reportDrift(long cpuMs) {
        if (baselineCpuMs == 0) {
            baselineCpuMs = cpuMs;
            return null;
        }
        double driftPct = 100.0 * (cpuMs - baselineCpuMs) / baselineCpuMs;
        if (Math.abs(driftPct) > properties.sandbox().benchmark().driftAlertPct()) {
            log.error("⚠️ MÁY CHẤM ĐỔI TỐC ĐỘ {}%: {}ms -> {}ms so với lần đo đầu. Nếu đang có "
                            + "contest thì bài chấm lúc này KHÔNG cùng điều kiện với bài chấm "
                            + "lúc đầu giờ — nghi ngờ throttle nhiệt (nfrplan rủi ro #5).",
                    String.format(java.util.Locale.ROOT, "%+.1f", driftPct),
                    baselineCpuMs, cpuMs);
        }
        return BigDecimal.valueOf(driftPct).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Gửi phép đo về API để nó vào bảng {@code host_benchmarks} — worker không có
     * {@code DataSource} và sẽ không bao giờ có (bất biến #3).
     *
     * <p>Không giữ lại khi gửi hỏng: {@code ResultBuffer} tồn tại để không mất <b>bài nộp</b>,
     * và một phép đo bị mất thì 15 phút nữa có phép đo khác.
     */
    private void publish(long cpuMs, BigDecimal driftPct) {
        if (api == null) {
            return;
        }
        api.reportBenchmark(new HostBenchmarkDto(
                properties.hostName(), properties.arch(), Instant.now(), cpuMs, factor,
                properties.sandbox().benchmark().calibrated(), driftPct));
    }

    private List<Long> runSamples() {
        List<Long> samples = new ArrayList<>();
        try (IsolateBox box = IsolateBox.open(properties.sandbox(), boxId,
                properties.sandbox().cache().dir().resolve("meta"))) {

            box.write(SOURCE, readWorkload());
            IsolateBox.Execution compile = box.execute(
                    IsolateCommand.compile(properties.sandbox(), boxId, box.metaFile(),
                            60_000, 524_288,
                            CommandTemplate.expand(
                                    "g++ -std=gnu++20 -O2 -pipe -static -o {bin} {src}",
                                    SOURCE, 65_536, properties.sandbox().programPath())),
                    null, 65_536, 65_536, Duration.ofSeconds(120));
            if (compile.meta().outcome() != IsolateMeta.Outcome.OK) {
                log.warn("Không biên dịch được tải chuẩn: {}", compile.meta().diagnostic());
                return samples;
            }

            byte[] binary = box.takeRegularFile(CommandTemplate.BINARY_NAME);
            for (int i = 0; i < properties.sandbox().benchmark().samples(); i++) {
                box.reset();
                box.writeExecutable(CommandTemplate.BINARY_NAME, binary);
                IsolateBox.Execution run = box.execute(
                        IsolateCommand.run(properties.sandbox(), boxId, box.metaFile(),
                                60_000, 120_000, 262_144,
                                List.of(IsolateCommand.BOX_DIR + '/' + CommandTemplate.BINARY_NAME)),
                        null, 4_096, 4_096, Duration.ofSeconds(180));
                if (run.meta().outcome() == IsolateMeta.Outcome.OK) {
                    samples.add(run.meta().cpuTimeMs());
                }
            }
        }
        return samples;
    }

    /** Trung vị, không trung bình: một lần bị hệ điều hành cướp CPU kéo trung bình đi rất xa. */
    private static long median(List<Long> samples) {
        if (samples.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(samples);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static String readWorkload() {
        try (InputStream in = HostBenchmark.class
                .getResourceAsStream("/benchmark/host-benchmark.cpp")) {
            if (in == null) {
                throw new IllegalStateException("Thiếu /benchmark/host-benchmark.cpp");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được tải chuẩn", e);
        }
    }
}
