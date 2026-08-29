package dev.oj.problems.application;

import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ★ Đọc và kiểm một gói testdata — FR-PROB-03, Bước 4.10.
 *
 * <h2>Bốn phép kiểm, và mỗi phép chặn một cách hỏng khác nhau</h2>
 * <ol>
 *   <li><b>Tên file theo danh sách CHO PHÉP</b> ({@link TestdataLimits#TEN_FILE_TEST}) — chặn
 *       {@code ../}, đường dẫn tuyệt đối, và mọi biến thể của chúng bằng cách không kể tên
 *       chúng.</li>
 *   <li><b>Đếm byte THẬT trong lúc đọc</b>, không tin {@code ZipEntry.getSize()}. Trường đó
 *       do người tạo file ghi và không ai kiểm — một zip bomb khai 1KB rồi giải nén ra 10GB.</li>
 *   <li><b>Tỉ lệ nén ≤ 100:1</b>, kiểm <i>trong lúc</i> đọc chứ không sau khi xong. Trần dung
 *       lượng một mình chỉ phát hiện sau khi đã đọc ngần ấy byte.</li>
 *   <li><b>{@code problem.yaml} bằng {@link SafeConstructor}</b> — xem mục dưới.</li>
 * </ol>
 *
 * <h2>★ SnakeYAML mặc định là một lỗ hổng thực thi mã từ xa</h2>
 * {@code new Yaml().load(...)} <b>khởi tạo được lớp Java bất kỳ</b> mà tài liệu YAML nêu tên
 * ({@code !!javax.script.ScriptEngineManager ...}). Với một file do người ngoài tải lên, đó là
 * RCE — trên chính tiến trình đang giữ đường nộp bài. {@link SafeConstructor} chỉ dựng các
 * kiểu nguyên thuỷ, {@code List} và {@code Map}, và đó là toàn bộ thứ mục lục này cần.
 *
 * <p>{@link LoaderOptions} chặn nốt hai đường còn lại: bom phình alias
 * ({@code setMaxAliasesForCollections}) và một tài liệu YAML khổng lồ trong một file nén nhỏ.
 *
 * <h2>★ Vì sao KHÔNG có phép kiểm symlink</h2>
 * {@code frplan.md} liệt kê "chặn symlink" cùng với {@code ..} và đường dẫn tuyệt đối. Ba thứ
 * đó chống cùng một mối nguy: <i>ghi ra ngoài thư mục đích</i>. Nhưng lớp này
 * <b>không bao giờ ghi ra hệ thống tệp</b> — mọi nội dung đi thẳng vào kho content-addressed
 * dưới khoá là sha256 của chính nó.
 *
 * <p>Nên một entry symlink chỉ là một file có nội dung là một chuỗi đường dẫn, và nó được lưu
 * như một chuỗi vô hại. Mối nguy bị loại bỏ <b>theo cấu trúc</b> chứ không bằng một phép kiểm
 * có thể quên — cùng lập luận đã dùng cho {@code alg=none} ở {@code Jwt}.
 *
 * <h2>Thứ tự test là thứ tự tên file, và mục lục chỉ nói cái nào là sample</h2>
 * Cách khác là liệt kê tường minh 1000 cặp trong YAML — thứ không ai viết tay, nên nó sẽ được
 * sinh ra bởi một script, và lúc đó một dòng sai trong script là một test bị bỏ qua trong im
 * lặng. Ghép theo tên thì <b>mọi file {@code .in} có mặt đều được nạp</b>, và thiếu file
 * {@code .out} tương ứng là một lỗi ồn ào.
 */
@Component
public class ZipTestdataValidator {

    /**
     * Một cặp test đã được xác nhận có mặt đủ hai đầu.
     *
     * @param ordinal 1..N theo thứ tự tên file
     */
    public record CapTest(int ordinal, String tenInput, String tenOutput, boolean laSample) {
    }

    /**
     * @param tongByte tổng byte sau giải nén, đếm thật
     */
    public record KetQua(List<CapTest> cacTest, long tongByte) {
    }

    /**
     * Đọc gói, kiểm mọi ràng buộc, <b>không ghi gì cả</b>.
     *
     * <p>FR-PROB-03: <i>"validate {@code problem.yaml} trước khi ghi bất cứ file nào"</i>. Ở
     * đây còn mạnh hơn — cả lượt đọc này không ghi một byte nào ra ngoài, nên một gói hỏng ở
     * test thứ 999 cũng không để lại nửa bộ test trong kho.
     *
     * @param zip     luồng gói. Người gọi đóng
     * @param zipByte kích thước gói nén, để tính tỉ lệ nén
     */
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
        Set<Integer> sample = docSample(manifestTho);
        return new KetQua(ghepCap(byteTheoTen.keySet(), sample), tongByte);
    }

    // -------------------------------------------------------------------------

    /**
     * Ghép {@code X.in} với {@code X.out} theo tên, sắp xếp theo thứ tự chữ cái.
     *
     * <p>Sắp xếp là điều kiện để "test số 7" nghĩa như nhau ở mọi lần nạp. Không sắp thì thứ
     * tự phụ thuộc thứ tự entry trong file ZIP — thứ do công cụ nén quyết định, và khác nhau
     * giữa {@code zip} của Linux với Explorer của Windows.
     */
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

    /** ★ {@link SafeConstructor} — xem javadoc của class. Đổi dòng này là mở lại một đường RCE. */
    private static Set<Integer> docSample(byte[] manifestTho) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(TestdataLimits.MAX_SAMPLE_BYTES);

        Object doc;
        try {
            doc = new Yaml(new SafeConstructor(options))
                    .load(new String(manifestTho, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw loi("problem.manifest_hong",
                    TestdataLimits.MANIFEST + " không phải YAML hợp lệ.");
        }
        Set<Integer> sample = new TreeSet<>();
        // Một manifest rỗng, hoặc chỉ có comment, parse ra null. Đó là "không khai gì", và
        // mặc định của "không khai gì" phải là mức KÍN nhất: mọi test đều ẩn. Coi nó là lỗi
        // thì một đề không có sample nào không nạp được, mà đó là một đề hoàn toàn hợp lệ.
        if (doc == null) {
            return sample;
        }
        if (!(doc instanceof Map<?, ?> map)) {
            throw loi("problem.manifest_hong",
                    TestdataLimits.MANIFEST + " phải là một ánh xạ khoá–giá trị.");
        }

        Object ds = map.get("samples");
        if (ds == null) {
            return sample;
        }
        if (!(ds instanceof List<?> list)) {
            throw loi("problem.manifest_samples",
                    "Trường 'samples' phải là một danh sách số thứ tự test, ví dụ [1, 2].");
        }
        for (Object o : list) {
            if (!(o instanceof Number n) || n.intValue() < 1
                    || n.intValue() > TestdataLimits.MAX_TEST) {
                throw loi("problem.manifest_samples",
                        "Trường 'samples' chỉ nhận số thứ tự test từ 1 tới "
                                + TestdataLimits.MAX_TEST + ".");
            }
            sample.add(n.intValue());
        }
        return sample;
    }

    /**
     * Đếm byte thật, <b>không tin {@code ZipEntry.getSize()}</b>: trường đó do người tạo file
     * ghi và không ai kiểm — một zip bomb khai 1KB rồi giải nén ra 10GB.
     */
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

    /** Kiểm TRONG LÚC đọc, không sau khi xong — xem javadoc của {@link TestdataLimits}. */
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
