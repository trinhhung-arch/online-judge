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

@Component
public class TestdataImportJob implements JobHandler {

    private final ZipTestdataValidator validator;
    private final TestdataStore store;
    private final TestdataRepository testdata;
    private final AuditLog auditLog;

    private final NghiaVuChamLai chamLai;

    public TestdataImportJob(ZipTestdataValidator validator, TestdataStore store,
                             TestdataRepository testdata, AuditLog auditLog,
                             NghiaVuChamLai chamLai) {
        this.validator = validator;
        this.store = store;
        this.testdata = testdata;
        this.auditLog = auditLog;
        this.chamLai = chamLai;
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
        Long rejudgeJobId = chamLai.thucHien(ctx, problemId, version, nguoiTao);
        auditLog.ghi("PROBLEM_TESTDATA_IMPORTED", "problem", problemId,
                rejudgeJobId == null
                        ? Map.of("version", version, "testCount", cacTest.size(),
                                "totalBytes", kiem.tongByte())
                        : Map.of("version", version, "testCount", cacTest.size(),
                                "totalBytes", kiem.tongByte(), "rejudgeJobId", rejudgeJobId));
        ctx.ghiSuKien("INFO", "Xong. Đề đã chuyển sang phiên bản " + version);
    }

    private record NoiDung(String sha256, int soByte, String vanBan) {
    }

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
                    continue;
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
