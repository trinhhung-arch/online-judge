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

@Component
public class HostBenchmark {

    private static final Logger log = LoggerFactory.getLogger(HostBenchmark.class);
    private static final String SOURCE = "Bench.cpp";

    private final WorkerProperties properties;
    private final JudgeApiClient api;
    private final int boxId;

    private volatile BigDecimal factor;
    private volatile long baselineCpuMs;

    public HostBenchmark(WorkerProperties properties, JudgeApiClient api) {
        this.properties = properties;
        this.api = api;
        this.boxId = properties.sandbox().firstBoxId() + properties.slots();
        this.factor = properties.hostFactor();
    }

    public BigDecimal current() {
        return factor;
    }

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
            log.warn("Không đo được tốc độ máy, giữ host_factor = {}: {}", factor, e.toString());
        }
    }

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
