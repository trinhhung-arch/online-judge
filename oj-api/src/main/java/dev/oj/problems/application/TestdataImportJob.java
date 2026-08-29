package dev.oj.problems.application;

import dev.oj.platform.audit.AuditLog;
import dev.oj.platform.jobs.JobContext;
import dev.oj.platform.jobs.JobHandler;
import dev.oj.platform.jobs.JobType;
import dev.oj.problems.application.port.TestdataRepository;
import dev.oj.problems.application.port.TestdataStore;
import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ★ Nạp một gói testdata — FR-PROB-03, 04. Bước 4.10, chạy trên khung job của Bước 4.7b.
 *
 * <h2>Vì sao đây phải là job nền chứ không phải một request</h2>
 * Quy tắc 5 của {@code frplan.md}: <i>mọi thao tác có thể vượt 5 giây là job nền có tiến độ</i>.
 * Một gói 200MB với 1000 test là 2000 lượt băm và 2000 lượt ghi lên kho — hàng phút, không
 * phải hàng giây. Giữ nó trong một request HTTP nghĩa là một connection treo hàng phút, một
 * timeout của proxy làm mất toàn bộ công việc, và không có cách nào biết nó đang tới đâu.
 *
 * <p>Đây cũng chính là xung đột thứ tự mà {@code build-order.md} PHẦN 6 nêu ra, và là lý do
 * hạ tầng job được kéo từ M6 lên tuần 7 (phương án (a)).
 *
 * <h2>★ Hai lượt đọc, và lượt đầu KHÔNG ghi một byte nào</h2>
 * FR-PROB-03 đòi <i>"validate {@code problem.yaml} trước khi ghi bất cứ file nào"</i>. Lượt
 * một chỉ kiểm; lượt hai mới ghi. Nhờ đó một gói hỏng ở test thứ 999 <b>không để lại nửa bộ
 * test</b> trong kho và trong database.
 *
 * <p>Cái giá là đọc gói hai lần. Với một job nền thì đó là vài giây, và nó mua lấy tính chất
 * "hỏng thì không để lại gì" — thứ đáng giá hơn hẳn.
 *
 * <h2>★ Chạy lại an toàn, và đó là điều kiện để Quy tắc 5 có nghĩa</h2>
 * Job này <b>sẽ</b> bị chạy lại: instance chết giữa chừng, lease hết hạn, {@code JobRunner}
 * nhặt lên từ {@code PAUSED}. Hai tính chất làm việc đó vô hại:
 *
 * <ul>
 *   <li>Kho là <b>content-addressed</b> — ghi lại cùng một nội dung cho cùng một khoá, và
 *       {@code daCo()} bỏ qua thứ đã có.</li>
 *   <li>Mọi câu ghi database là {@code ON CONFLICT ... DO UPDATE} với dữ liệu giống hệt.</li>
 * </ul>
 *
 * <p>Nên chạy lại chỉ tốn <i>thời gian</i>, không tạo dữ liệu sai. Đó là lý do vị trí lưu
 * trong {@code cursor_state} chỉ cần một trường: số phiên bản đang dựng.
 *
 * <h2>★ Bước cuối cùng là một câu UPDATE, và thứ tự đó quan trọng</h2>
 * {@code kichHoatPhienBan} chạy sau khi mọi test đã nằm đủ trong kho và trong database. Chết
 * trước dòng đó thì đề vẫn dùng phiên bản cũ, và <b>không một bài nộp nào bị chấm bằng nửa bộ
 * test</b> — kết quả sai âm thầm là thứ tệ nhất một Online Judge có thể tạo ra.
 */
@Component
public class TestdataImportJob implements JobHandler {

    private final ZipTestdataValidator validator;
    private final TestdataStore store;
    private final TestdataRepository testdata;
    private final AuditLog auditLog;

    public TestdataImportJob(ZipTestdataValidator validator, TestdataStore store,
                             TestdataRepository testdata, AuditLog auditLog) {
        this.validator = validator;
        this.store = store;
        this.testdata = testdata;
        this.auditLog = auditLog;
    }

    @Override
    public JobType type() {
        return JobType.TESTDATA_IMPORT;
    }

    @Override
    public void chay(JobContext ctx) {
        long problemId = so(ctx.params(), "problemId");
        String zipSha = (String) ctx.params().get("zipSha256");
        long zipByte = so(ctx.params(), "zipBytes");
        long nguoiTao = ctx.nguoiTao() == null ? 0L : ctx.nguoiTao();

        ctx.ghiSuKien("INFO", "Đang kiểm gói testdata");
        ZipTestdataValidator.KetQua kiem;
        try (InputStream in = store.doc(zipSha)) {
            kiem = validator.kiem(in, zipByte);
        } catch (IOException e) {
            throw ProblemsException.khongHopLe("problem.zip_hong", "Không đọc được gói đã tải lên.");
        }

        List<ZipTestdataValidator.CapTest> cacTest = kiem.cacTest();
        ctx.tienDo(0, cacTest.size());

        int version = viTri(ctx, problemId);
        testdata.taoPhienBan(problemId, version, zipSha, cacTest.size(), kiem.tongByte(), nguoiTao);
        ctx.ghiSuKien("INFO", "Đang nạp " + cacTest.size() + " testcase (phiên bản " + version + ")");

        Map<String, NoiDung> theoTen = docVaLuu(ctx, zipSha, cacTest);
        ghiMetadata(ctx, problemId, version, cacTest, theoTen);

        testdata.kichHoatPhienBan(problemId, version);
        auditLog.ghi("PROBLEM_TESTDATA_IMPORTED", "problem", problemId,
                Map.of("version", version, "testCount", cacTest.size(),
                        "totalBytes", kiem.tongByte()));
        ctx.ghiSuKien("INFO", "Xong. Đề đã chuyển sang phiên bản " + version);
    }

    // -------------------------------------------------------------------------

    /** Nội dung một file trong gói: hash, kích thước, và <b>văn bản chỉ khi là sample</b>. */
    private record NoiDung(String sha256, int soByte, String vanBan) {
    }

    /**
     * Lượt đọc thứ hai: băm, đẩy lên kho, và giữ lại văn bản của <b>riêng</b> test sample.
     *
     * <p>Giữ văn bản của test ẩn trong bộ nhớ là một bước gần hơn tới việc nó lọt ra ngoài
     * (bất biến #1). Ở đây nó không những không được lưu vào Postgres mà còn không được giữ
     * lại quá lúc đẩy lên kho.
     */
    private Map<String, NoiDung> docVaLuu(JobContext ctx, String zipSha,
                                          List<ZipTestdataValidator.CapTest> cacTest) {
        Map<String, Boolean> laSample = new HashMap<>();
        for (var cap : cacTest) {
            laSample.put(cap.tenInput(), cap.laSample());
            laSample.put(cap.tenOutput(), cap.laSample());
        }

        Map<String, NoiDung> theoTen = new HashMap<>();
        int daDoc = 0;
        try (ZipInputStream in = new ZipInputStream(store.doc(zipSha))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory() || !laSample.containsKey(entry.getName())) {
                    continue;   // problem.yaml đã đọc ở lượt một
                }
                ctx.kiemHuy();
                byte[] noiDung = doc(in);
                String sha = bam(noiDung);
                if (!store.daCo(sha)) {
                    store.luu(sha, new java.io.ByteArrayInputStream(noiDung), noiDung.length);
                }
                theoTen.put(entry.getName(), new NoiDung(sha, noiDung.length,
                        vanBanSample(laSample.get(entry.getName()), noiDung)));

                if (++daDoc % 20 == 0) {
                    // Nhịp tim: gia hạn lease. Im lặng quá lâu là bị nhặt lại và chạy song
                    // song với chính mình — xem JobHandler, hợp đồng số 2.
                    ctx.tienDo(daDoc / 2, cacTest.size());
                }
            }
        } catch (IOException e) {
            throw ProblemsException.khongHopLe("problem.zip_hong", "Không đọc được gói đã tải lên.");
        }
        return theoTen;
    }

    private void ghiMetadata(JobContext ctx, long problemId, int version,
                             List<ZipTestdataValidator.CapTest> cacTest,
                             Map<String, NoiDung> theoTen) {
        int daXong = 0;
        for (var cap : cacTest) {
            ctx.kiemHuy();
            NoiDung vao = theoTen.get(cap.tenInput());
            NoiDung ra = theoTen.get(cap.tenOutput());
            long testcaseId = testdata.themTestcase(problemId, version, cap.ordinal(),
                    cap.laSample(), vao.sha256(), ra.sha256(), vao.soByte(), ra.soByte());

            if (cap.laSample()) {
                if (vao.vanBan() == null || ra.vanBan() == null) {
                    throw ProblemsException.khongHopLe("problem.sample_qua_lon",
                            "Test sample số " + cap.ordinal() + " vượt "
                                    + TestdataLimits.MAX_SAMPLE_BYTES + " byte. "
                                    + "Test công khai phải đủ nhỏ để hiển thị trên trang đề.");
                }
                testdata.themNoiDungSample(testcaseId, vao.vanBan(), ra.vanBan());
            }
            ctx.tienDo(++daXong, cacTest.size());
        }
    }

    /**
     * Số phiên bản đang dựng, lấy từ vị trí đã lưu nếu job này từng chạy dở.
     *
     * <p>Không có nó thì mỗi lần restart tạo thêm một phiên bản mới, và một job bị nhặt lại
     * ba lần để lại ba phiên bản testdata mà chỉ một cái được dùng.
     */
    private int viTri(JobContext ctx, long problemId) {
        Object daLuu = ctx.viTriDaLuu().get("version");
        if (daLuu instanceof Number n) {
            return n.intValue();
        }
        int version = testdata.phienBanKeTiep(problemId);
        ctx.luuViTri(Map.of("version", version));
        return version;
    }

    private static String vanBanSample(boolean laSample, byte[] noiDung) {
        if (!laSample || noiDung.length > TestdataLimits.MAX_SAMPLE_BYTES) {
            return null;
        }
        return new String(noiDung, StandardCharsets.UTF_8);
    }

    private static byte[] doc(ZipInputStream in) throws IOException {
        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        byte[] dem = new byte[8192];
        int n;
        while ((n = in.read(dem)) > 0) {
            if (ra.size() + n > TestdataLimits.MAX_MOT_FILE_BYTES) {
                throw ProblemsException.khongHopLe("problem.file_test_qua_lon",
                        "Một file test vượt "
                                + (TestdataLimits.MAX_MOT_FILE_BYTES / 1024 / 1024) + "MB.");
            }
            ra.write(dem, 0, n);
        }
        return ra.toByteArray();
    }

    private static String bam(byte[] noiDung) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(noiDung));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không có SHA-256", e);
        }
    }

    private static long so(Map<String, Object> params, String khoa) {
        Object v = params.get(khoa);
        if (!(v instanceof Number n)) {
            throw ProblemsException.khongHopLe("problem.job_thieu_tham_so",
                    "Công việc thiếu tham số bắt buộc.");
        }
        return n.longValue();
    }
}
