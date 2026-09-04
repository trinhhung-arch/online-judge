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

/**
 * ★ Đọc {@code problem.yaml} — FR-PROB-03.
 *
 * <h2>Mục lục này cố ý NHỎ, và nó nhỏ vì một lý do về quyền</h2>
 * Nó trả lời đúng một câu: <b>test nào là ví dụ</b>. Không giới hạn thời gian, không bộ nhớ,
 * không checker, không mã đề. Những thứ ấy đặt ở trang soạn đề, đi qua
 * {@code AuthorProblemUseCase} — nơi có {@code @RequiresRole} và một dòng {@code audit_log}.
 *
 * <p>Đọc chúng từ gói testdata sẽ mở một đường đổi tham số chấm bài <b>không ai ghi lại</b>:
 * nạp một gói là đổi được giới hạn thời gian của một đề đang trong kỳ thi. Trên một hệ thống
 * bán sự công bằng, đó không phải một tiện ích.
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
 * <h2>Tách khỏi {@link ZipTestdataValidator} vì hai việc khác nhau</h2>
 * Bên kia đọc một luồng ZIP không tin được và đếm byte. Ở đây phân tích một tài liệu YAML.
 * Gộp lại thì file vượt trần 300 dòng của {@code CLAUDE.md} mục 7, và trần ấy có lý.
 */
final class TestdataManifest {

    /** Khoá DUY NHẤT mục lục này đọc. Xem javadoc của {@link #kiemKhoaLa}. */
    private static final String KHOA_SAMPLES = "samples";

    /**
     * Khoá mô tả chính cái đề — chúng thuộc về trang soạn đề, không thuộc gói testdata.
     *
     * <p>Đây không phải chuyện gọn gàng. Nếu {@code time_limit_ms} hay {@code checker} đọc
     * được từ gói thì <b>nạp testdata sửa được tham số chấm bài</b> — mà đường nạp testdata
     * không đi qua {@code AuthorProblemUseCase}, nên không để lại dòng {@code audit_log} nào.
     * Một hệ thống bán sự công bằng không được có đường đổi giới hạn thời gian mà không ai
     * ghi lại.
     */
    private static final Set<String> KHOA_CUA_TRANG_SOAN_DE = Set.of(
            "code", "title", "statement", "statement_md",
            "time_limit_ms", "memory_limit_kb", "output_limit_kb",
            "checker", "checker_type", "checker_epsilon",
            "scoring", "scoring_mode", "feedback_level",
            "allow_public_solutions", "allow_view_solution_after_solved");

    /**
     * Khoá mô tả danh sách test — thứ tự đã do TÊN FILE quyết định.
     *
     * <p>Đọc thêm một danh sách ở đây là tạo nguồn sự thật thứ hai cho cùng một thứ, và hai
     * nguồn ấy sẽ bất đồng: danh sách trỏ tới file không có trong gói, bỏ sót file đang có,
     * hoặc kể tên một file hai lần. Thứ tự tên file thì nhìn vào ZIP là kiểm được.
     */
    private static final Set<String> KHOA_VE_DANH_SACH_TEST =
            Set.of("tests", "test", "testcases", "cases", "testdata");

    /**
     * ★ Khoá lạ là LỖI, không phải thứ bỏ qua trong im lặng.
     *
     * <h2>Vì sao im lặng ở đây tệ hơn từ chối</h2>
     * Một mục lục viết đầy đủ và hợp lý với con người — {@code code}, {@code time_limit_ms},
     * {@code tests:} kèm {@code sample: true} cho từng test — trông y như nó đang có tác dụng.
     * Bỏ qua nó thì gói nạp <b>thành công</b>, người ra đề tin rằng test 1 và 2 là ví dụ, và
     * sự thật là không test nào được đánh dấu. Không có lỗi, không có cảnh báo, và cái sai chỉ
     * lộ ra khi một thí sinh hỏi vì sao không xem được test mẫu — nếu có ai hỏi.
     *
     * <p>Đây là một chuyện đã xảy ra thật, và nó xảy ra với đúng một người ra đề cẩn thận:
     * người viết mục lục sơ sài thì không gặp, người viết kỹ thì gặp.
     *
     * <h2>Danh sách TRẮNG, không phải danh sách đen</h2>
     * Một danh sách đen chỉ bắt được những khoá ai đó đã nghĩ ra. Danh sách trắng bắt cả những
     * khoá chưa ai nghĩ ra — kể cả một khoá gõ sai chính tả như {@code sample} (thiếu 's'),
     * vốn là cách hỏng âm thầm nhất trong cả nhóm.
     */
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

    /** Nói thứ vừa viết THẬT SỰ sống ở đâu — một câu "khoá không hợp lệ" trơ thì bỏ mặc người ta. */
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
