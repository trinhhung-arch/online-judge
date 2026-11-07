package dev.oj.problems.application;

import dev.oj.problems.domain.ProblemsException;
import dev.oj.problems.domain.TestdataLimits;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class TestdataManifest {

    private static final String KHOA_SAMPLES = "samples";

    private static final Set<String> KHOA_CUA_TRANG_SOAN_DE = Set.of(
            "code", "title", "statement", "statement_md",
            "time_limit_ms", "memory_limit_kb", "output_limit_kb",
            "checker", "checker_type", "checker_epsilon",
            "scoring", "scoring_mode", "feedback_level",
            "allow_public_solutions", "allow_view_solution_after_solved");

    private static final Set<String> KHOA_VE_DANH_SACH_TEST =
            Set.of("tests", "test", "testcases", "cases", "testdata");

    private static void kiemKhoaLa(Map<?, ?> map) {
        for (Object khoaTho : map.keySet()) {
            String khoa = String.valueOf(khoaTho);
            if (KHOA_SAMPLES.equals(khoa)) {
                continue;
            }
            throw loi("problem.manifest_khoa_la",
                    "Khoá '" + khoa + "' trong " + TestdataLimits.MANIFEST + " không được đọc. "
                            + giaiThich(khoa));
        }
    }

    private static String giaiThich(String khoa) {
        if (KHOA_CUA_TRANG_SOAN_DE.contains(khoa)) {
            return "Thiết lập này đặt ở trang soạn đề, không ở gói testdata.";
        }
        if (KHOA_VE_DANH_SACH_TEST.contains(khoa)) {
            return "Thứ tự test lấy từ tên file trong tests/, không từ mục lục. "
                    + "Dùng 'samples: [1, 2]' để đánh dấu test nào là ví dụ.";
        }
        return TestdataLimits.MANIFEST + " chỉ nhận đúng một khoá: "
                + "'samples: [1, 2]' — danh sách số thứ tự của các test ví dụ.";
    }

    static Set<Integer> docSample(byte[] manifestTho) {
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
        if (doc == null) {
            return sample;
        }
        if (!(doc instanceof Map<?, ?> map)) {
            throw loi("problem.manifest_hong",
                    TestdataLimits.MANIFEST + " phải là một ánh xạ khoá–giá trị.");
        }

        kiemKhoaLa(map);

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

    private static ProblemsException loi(String code, String thongDiep) {
        return ProblemsException.khongHopLe(code, thongDiep);
    }

    private TestdataManifest() {
    }
}
