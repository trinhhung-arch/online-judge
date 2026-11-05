package dev.oj.problems.application;

import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ZipTestdataValidator {

    public record CapTest(int ordinal, String tenInput, String tenOutput, boolean laSample) {
    }

    public record KetQua(List<CapTest> cacTest, long tongByte) {
    }

    public KetQua kiem(InputStream zip, long zipByte) {
        if (zipByte > TestdataLimits.MAX_ZIP_BYTES) {
            throw loi("problem.zip_qua_lon",
                    "Gói testdata vượt " + (TestdataLimits.MAX_ZIP_BYTES / 1024 / 1024) + "MB.");
        }

        Map<String, Long> byteTheoTen = new LinkedHashMap<>();
        byte[] manifestTho = null;
        long tongByte = 0;

        try (ZipInputStream in = new ZipInputStream(zip)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String ten = entry.getName();
                boolean laManifest = TestdataLimits.MANIFEST.equals(ten);
                if (!laManifest && !TestdataLimits.TEN_FILE_TEST.matcher(ten).matches()) {
                    throw loi("problem.zip_ten_file_la",
                            "Gói chứa một mục không hợp lệ: '" + ten + "'. Chỉ chấp nhận "
                                    + TestdataLimits.MANIFEST + " và tests/<tên>.in|.out.");
                }
                if (byteTheoTen.size() >= TestdataLimits.MAX_TEST * 2 + 1) {
                    throw quaNhieuTest();
                }

                long soByte;
                if (laManifest) {
                    manifestTho = docGioiHan(in, TestdataLimits.MAX_SAMPLE_BYTES);
                    soByte = manifestTho.length;
                } else {
                    soByte = demByte(in);
                    byteTheoTen.put(ten, soByte);
                }

                tongByte += soByte;
                kiemPhinh(tongByte, zipByte);
            }
        } catch (IOException e) {
            throw loi("problem.zip_hong", "Không đọc được gói: file không phải ZIP hợp lệ.");
        }

        if (manifestTho == null) {
            throw loi("problem.zip_thieu_manifest",
                    "Gói thiếu " + TestdataLimits.MANIFEST + " ở thư mục gốc.");
        }
        Set<Integer> sample = TestdataManifest.docSample(manifestTho);
        return new KetQua(ghepCap(byteTheoTen.keySet(), sample), tongByte);
    }

    private static List<CapTest> ghepCap(Collection<String> ten, Set<Integer> sample) {
        Set<String> goc = new TreeSet<>();
        for (String t : ten) {
            goc.add(t.substring(0, t.lastIndexOf('.')));
        }
        if (goc.isEmpty()) {
            throw loi("problem.zip_khong_co_test", "Gói không chứa testcase nào.");
        }
        if (goc.size() > TestdataLimits.MAX_TEST) {
            throw quaNhieuTest();
        }

        List<CapTest> ketQua = new ArrayList<>(goc.size());
        int ordinal = 1;
        for (String g : goc) {
            String in = g + ".in";
            String out = g + ".out";
            if (!ten.contains(in) || !ten.contains(out)) {
                throw loi("problem.zip_thieu_cap",
                        "Test '" + g.substring(g.indexOf('/') + 1) + "' thiếu file "
                                + (ten.contains(in) ? ".out" : ".in") + ".");
            }
            ketQua.add(new CapTest(ordinal, in, out, sample.contains(ordinal)));
            ordinal++;
        }
        return ketQua;
    }

    private long demByte(ZipInputStream in) throws IOException {
        byte[] dem = new byte[8192];
        long tong = 0;
        int n;
        while ((n = in.read(dem)) > 0) {
            tong += n;
            if (tong > TestdataLimits.MAX_GIAI_NEN_BYTES) {
                throw quaLon();
            }
        }
        return tong;
    }

    private static byte[] docGioiHan(ZipInputStream in, int tran) throws IOException {
        ByteArrayOutputStream ra = new ByteArrayOutputStream();
        byte[] dem = new byte[8192];
        int n;
        while ((n = in.read(dem)) > 0) {
            if (ra.size() + n > tran) {
                throw loi("problem.manifest_qua_lon",
                        TestdataLimits.MANIFEST + " quá lớn (tối đa " + tran + " byte).");
            }
            ra.write(dem, 0, n);
        }
        return ra.toByteArray();
    }

    private static void kiemPhinh(long tongByte, long zipByte) {
        if (tongByte > TestdataLimits.MAX_GIAI_NEN_BYTES) {
            throw quaLon();
        }
        if (zipByte > 0 && tongByte / Math.max(1, zipByte) > TestdataLimits.MAX_TI_LE_NEN) {
            throw loi("problem.zip_bomb",
                    "Tỉ lệ nén vượt " + TestdataLimits.MAX_TI_LE_NEN + ":1. "
                            + "Gói này giải nén ra lớn hơn nhiều lần kích thước tệp.");
        }
    }

    private static ProblemsException quaLon() {
        return loi("problem.zip_giai_nen_qua_lon",
                "Tổng dung lượng sau giải nén vượt "
                        + (TestdataLimits.MAX_GIAI_NEN_BYTES / 1024 / 1024 / 1024) + "GB.");
    }

    private static ProblemsException quaNhieuTest() {
        return loi("problem.zip_qua_nhieu_test",
                "Gói vượt " + TestdataLimits.MAX_TEST + " testcase.");
    }

    private static ProblemsException loi(String code, String thongDiep) {
        return ProblemsException.khongHopLe(code, thongDiep);
    }
}
