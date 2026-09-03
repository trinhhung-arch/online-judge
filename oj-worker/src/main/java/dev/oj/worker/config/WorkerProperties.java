package dev.oj.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Mọi con số của worker — một chỗ duy nhất, không rải trong code.
 *
 * <p><b>Không có thuộc tính nào liên quan tới database, và sẽ không bao giờ có</b> (bất biến
 * #3). Worker biết đúng một địa chỉ HTTP và một secret.
 *
 * @param hostName   khớp {@code judge_hosts.name}. API tra ra id; worker không biết id tồn tại
 * @param arch       {@code arm64} hoặc {@code amd64} — một con số thời gian không kèm kiến
 *                   trúc là một con số vô nghĩa ({@code nfrplan.md} 9.1)
 * @param slots      số box chấm song song. <b>Cố định theo cấu hình, KHÔNG theo số core</b>:
 *                   M1 Max có 10 core nhưng chạy 6 slot, vì chạy full core 10-15 phút sẽ
 *                   throttle và bài phút thứ 90 chấm chậm hơn bài phút thứ 5 — mất công bằng
 *                   ngay giữa contest (ADR 008)
 * @param lease      bản sao của {@code oj.judge.lease} phía API, dùng để <b>cảnh báo</b> khi
 *                   một lượt chấm sắp vượt hạn. Vượt rồi thì kết quả sẽ bị khoá lạc quan từ
 *                   chối, nên chấm tiếp là phí một slot
 * @param hostFactor hệ số hiệu chuẩn khởi điểm. {@code HostBenchmark} (Bước 2.9) đo lại lúc
 *                   khởi động và mỗi 15 phút, rồi ghi đè giá trị dùng thật
 * @param sandbox    giới hạn của {@code isolate} — xem {@link Sandbox}
 */
@ConfigurationProperties(prefix = "oj.worker")
public record WorkerProperties(
        String hostName,
        String arch,
        int slots,
        Duration lease,
        Duration idlePoll,
        Duration shutdownGrace,
        Duration requestTimeout,
        Duration resultRetryMin,
        Duration resultRetryMax,
        String apiBaseUrl,
        String internalSecret,
        java.math.BigDecimal hostFactor,
        Sandbox sandbox) {

    public WorkerProperties {
        if (hostName == null || hostName.isBlank()) {
            throw new IllegalStateException("oj.worker.host-name bắt buộc — API dùng nó để tra "
                    + "judge_hosts và để ghi vào judge_runs");
        }
        if (slots < 1 || slots > 32) {
            throw new IllegalStateException(
                    "oj.worker.slots ngoài [1..32]: " + slots + ". Khớp CHECK trên judge_hosts, "
                            + "và đọc ADR 008 trước khi tăng con số này");
        }
        if (apiBaseUrl == null || apiBaseUrl.isBlank()) {
            throw new IllegalStateException("oj.worker.api-base-url bắt buộc");
        }
        // Cùng tinh thần với AppProperties.Internal phía API: thà không khởi động được còn hơn
        // chạy rồi nhận 401 cho mọi request mà không hiểu vì sao.
        if (internalSecret == null || internalSecret.length() < 32) {
            throw new IllegalStateException(
                    "Thiếu OJ_INTERNAL_SHARED_SECRET (cần >= 32 ký tự). Đây là thứ duy nhất "
                            + "cho worker quyền ghi verdict — không có giá trị mặc định, cố ý");
        }
        if (shutdownGrace == null || shutdownGrace.isNegative()) {
            throw new IllegalStateException(
                    "oj.worker.shutdown-grace không hợp lệ: " + shutdownGrace
                            + ". Đây là thời gian tối đa chờ slot chấm nốt bài đang chạy khi "
                            + "nhận SIGTERM (Bước 6.8). Đặt 0 là quay lại hành vi cắt ngang "
                            + "của M1, tức là mỗi lần deploy đội thêm 120 giây chờ reaper cho "
                            + "mỗi bài đang chấm dở");
        }
        if (idlePoll == null || idlePoll.isZero() || idlePoll.isNegative()) {
            throw new IllegalStateException("oj.worker.idle-poll phải dương");
        }
        if (sandbox == null) {
            throw new IllegalStateException("oj.worker.sandbox bắt buộc — không có mặc định "
                    + "ngầm cho thứ quyết định mã người lạ chạy trong cái gì");
        }
    }

    /**
     * Giới hạn của {@code isolate}. Ba nhóm, và ranh giới giữa chúng có ý nghĩa:
     *
     * <ul>
     *   <li><b>Không ở đây:</b> CPU, RAM, output của một lượt chạy. Chúng nằm trong
     *       {@code JudgeJobDto} vì chúng là thuộc tính của <i>đề bài</i>, không phải của máy
     *       chấm. Chép chúng vào file này là tạo ra hai nguồn sự thật, và ngày chúng lệch
     *       nhau thì cùng một bài AC trên máy này TLE trên máy kia.</li>
     *   <li><b>Ở đây:</b> những gì hợp đồng không mang — đường dẫn {@code isolate}, số tiến
     *       trình, số fd, thư mục bị giấu.</li>
     * </ul>
     *
     * @param enabled        {@code false} giữ nguyên {@code ScriptedJudgeRunner} của M1.
     *                       Đây <b>không</b> phải cửa hậu để bỏ sandbox trên máy thật: với
     *                       {@code false} thì không một dòng mã người dùng nào được chạy cả
     * @param isolateBinary  không dò trong {@code PATH}. Một {@code isolate} khác phiên bản
     *                       nằm sớm hơn trong {@code PATH} là đúng loại sự cố không ai đoán ra
     * @param boxRoot        {@code box_root} trong {@code /usr/local/etc/isolate}. Worker chỉ
     *                       đọc để biết box nằm đâu; {@code isolate} mới là bên tạo ra nó
     * @param firstBoxId     box id của slot 0. Hai worker trên cùng một máy phải đặt lệch nhau,
     *                       nếu không chúng giành cùng một box và cả hai cùng hỏng
     * @param watchdogSlack  chốt chặn cuối: {@code isolate} tự có giới hạn, nhưng nếu chính nó
     *                       treo thì worker phải giết. Cộng vào wall limit của lượt chạy
     * @param compile        giới hạn bước biên dịch — bất biến #4 tính cả bước này
     * @param run            giới hạn bước chạy
     * @param cache          nơi giữ binary đã biên dịch và testdata đã tải
     */
    public record Sandbox(
            boolean enabled,
            Path isolateBinary,
            Path boxRoot,
            int firstBoxId,
            Duration watchdogSlack,
            List<String> programPath,
            Compile compile,
            Run run,
            Cache cache,
            Benchmark benchmark) {

        public Sandbox {
            if (isolateBinary == null || boxRoot == null) {
                throw new IllegalStateException(
                        "oj.worker.sandbox.isolate-binary và .box-root bắt buộc");
            }
            // isolate cấp box id 0..999 (num_boxes mặc định). Test dùng dải 900+ để chạy
            // được trên máy đang có worker thật ở dải 0..5.
            if (firstBoxId < 0 || firstBoxId > 999) {
                throw new IllegalStateException("first-box-id ngoài [0..999]: " + firstBoxId);
            }
            programPath = programPath == null || programPath.isEmpty()
                    ? List.of("/usr/bin", "/bin")
                    : List.copyOf(programPath);
            if (compile == null || run == null || cache == null || benchmark == null) {
                throw new IllegalStateException("thiếu nhóm compile/run/cache/benchmark");
            }
        }

        /**
         * @param processes   biên dịch cần fork ({@code g++} gọi {@code cc1plus}, {@code as},
         *                    {@code collect2}), nên khác bước chạy
         * @param openFiles   {@code #include} lồng nhau mở rất nhiều file cùng lúc
         * @param maxFileSize trần {@code RLIMIT_FSIZE} — chặn một chương trình biên dịch ra
         *                    file 10GB
         * @param logLimit    trần log compiler đọc về. Cắt ở đây chứ không ở
         *                    {@code JudgeResultDto}: cắt sớm thì 100MB thông báo lỗi template
         *                    không bao giờ vào heap của worker
         */
        /**
         * @param pchDir thư mục chứa {@code bits/stdc++.h.gch} trên host, hoặc {@code null}
         *               nếu chưa dựng. Được gắn read-only vào box lúc biên dịch và lộ ra qua
         *               placeholder {@code {pch}} của {@code languages.compile_command}.
         *               <p>Thiếu nó thì {@code -I} trỏ vào một thư mục không tồn tại — GCC bỏ
         *               qua trong im lặng và biên dịch chậm như cũ. <b>Hỏng nhẹ, không hỏng
         *               nặng</b>: một máy chưa chạy {@code scripts/build-pch.sh} vẫn chấm
         *               đúng, chỉ chậm hơn.
         */
        public record Compile(int processes, int openFiles, DataSize maxFileSize,
                              DataSize logLimit, Path pchDir) {
        }

        /**
         * @param extraTime   khoảng ân hạn của {@code isolate}: chương trình vượt giờ không bị
         *                    giết ngay, nhờ đó <b>thời gian chạy thật</b> được báo cáo thay vì
         *                    đúng bằng giới hạn. Có nó thì trang bài nộp phân biệt được
         *                    "vượt 1ms" với "lặp vô hạn" (U2, {@code nfrplan.md} 6.2)
         * @param processes   {@code 1} cho ngôn ngữ đơn luồng. <b>Đây là một con số tạm của
         *                    M2:</b> Java 21 cần vài chục tiến trình cho GC và JIT. M3 (đa
         *                    ngôn ngữ) phải mang con số này trong bảng {@code languages} chứ
         *                    không phải trong file cấu hình của worker — nếu không thì "thêm
         *                    một ngôn ngữ = 1 dòng config, 0 dòng code" (M4-nfr) không còn đúng
         * @param openFiles   trần fd đồng thời
         * @param maxFileSize trần một file chương trình tự ghi ra
         * @param stderrLimit trần stderr đọc về — chỉ để chẩn đoán, không bao giờ về API
         * @param hiddenDirs  <b>thư mục gỡ khỏi mount mặc định của isolate.</b> Đo được:
         *                    {@code /proc} mặc định có mặt và {@code /proc/self/environ} đọc
         *                    được; {@code /tmp} mặc định ghi được. Cả hai bị gỡ ở bước chạy
         *                    (test tấn công 6 và 11 chính là hai ca đó).
         *                    <p>Đo thêm được: binary C++ dựng bằng {@code -static} còn chạy
         *                    được khi gỡ cả {@code /usr /bin /lib /lib64} — một profile chặt
         *                    hơn nữa. Không bật mặc định vì Python và Java (M3) cần chúng, và
         *                    một profile chỉ đúng cho một ngôn ngữ sẽ hỏng theo kiểu khó hiểu
         *                    đúng vào lúc thêm ngôn ngữ thứ hai
         */
        public record Run(Duration extraTime, int processes, int openFiles, DataSize maxFileSize,
                          DataSize stderrLimit, List<String> hiddenDirs) {

            public Run {
                hiddenDirs = hiddenDirs == null ? List.of() : List.copyOf(hiddenDirs);
                if (processes < 1) {
                    throw new IllegalStateException("run.processes phải >= 1");
                }
            }
        }

        /**
         * @param dir        thư mục cache. <b>Không nằm trong box</b> — testdata trong box là
         *                   bất biến #1 bị phá (một chương trình bốn dòng đọc thư mục là lộ
         *                   toàn bộ đáp án)
         * @param maxEntries trần số binary giữ lại. Cache đầy thì bỏ cái cũ nhất; nó chỉ là
         *                   tối ưu, mất hết cũng chỉ là biên dịch lại
         */
        public record Cache(Path dir, int maxEntries) {
        }

        /**
         * Hiệu chuẩn máy chấm (Bước 2.9, {@code nfrplan.md} 9.1 và rủi ro #5).
         *
         * @param interval       nhịp đo lại. 15 phút đủ để bắt throttle nhiệt <b>trong</b> một
         *                       contest, mà không tốn đáng kể tài nguyên
         * @param samples        số lần chạy mỗi lượt đo; lấy trung vị, không lấy trung bình —
         *                       một lần bị hệ điều hành cướp CPU sẽ kéo trung bình đi rất xa
         * @param referenceCpuMs thời gian tải chuẩn chạy trên <b>máy chấm chuẩn</b>.
         *                       {@code 0} = chưa hiệu chuẩn: worker vẫn đo và vẫn cảnh báo
         *                       drift, nhưng <b>không</b> đổi giới hạn chấm.
         *                       <p>Mặc định là 0 có chủ ý: máy chấm chuẩn (Mac M1 Max) chưa
         *                       được deploy tới Bước 2.10, và để một hằng số chưa ai đo quyết
         *                       định TLE của thí sinh thì tệ hơn hẳn so với không hiệu chuẩn
         * @param driftAlertPct  lệch quá bao nhiêu phần trăm so với lần đo đầu tiên của chính
         *                       máy này thì cảnh báo. Đây là cái bẫy nhiệt: máy chạy 90 phút
         *                       contest chậm dần, bài cuối giờ bị TLE oan, và không ai nhận ra
         */
        public record Benchmark(Duration interval, int samples, int referenceCpuMs,
                                double driftAlertPct) {

            public boolean calibrated() {
                return referenceCpuMs > 0;
            }
        }
    }
}
