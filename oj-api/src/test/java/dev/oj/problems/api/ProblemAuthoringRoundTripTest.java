package dev.oj.problems.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ Đọc-để-sửa phải trả về đúng những gì ghi sẽ đặt — FR-PROB-01.
 *
 * <h2>Lỗi có thật mà ca này canh</h2>
 * {@code GET .../edit} từng trả {@link ProblemResponse}, thiếu {@code checkerEpsilon} và
 * {@code allowPublicSolutions}. {@code PUT} thì ghi đè trọn bản ghi. Nên mở một đề
 * {@code float}, sửa mỗi tiêu đề rồi Lưu là epsilon thành {@code null} — đề vẫn chấm, chỉ
 * chấm sai, và không có thông báo nào vì về mặt kỹ thuật không có gì hỏng.
 *
 * <h2>Vì sao so bằng phản chiếu chứ không viết một ca cho mỗi trường</h2>
 * Vì lỗi này không đến từ một trường cụ thể — nó đến từ việc <b>thêm</b> một trường vào phía
 * ghi mà quên phía đọc. Một ca liệt kê mười trường bằng tay sẽ xanh mãi cho tới khi ai đó
 * thêm trường thứ mười một, tức là đúng lúc nó cần đỏ.
 */
class ProblemAuthoringRoundTripTest {

    private static Set<String> ten(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("★ mọi trường PUT ghi đều đọc lại được ở GET .../edit")
    void moi_truong_ghi_deu_doc_lai_duoc() {
        Set<String> ghi = ten(ProblemAuthoringRequest.class);
        Set<String> doc = ten(ProblemAuthoringResponse.class);

        List<String> thieu = ghi.stream().filter(t -> !doc.contains(t)).toList();

        assertThat(thieu)
                .as("PUT đặt %s nhưng GET .../edit không trả lại — sửa rồi lưu sẽ XOÁ chúng "
                        + "trong im lặng. Thêm vào ProblemAuthoringResponse.", thieu)
                .isEmpty();
    }

    @Test
    @DisplayName("★ phản hồi CÔNG KHAI vẫn không mang epsilon — sửa lỗi này không được nới nó ra")
    void phan_hoi_cong_khai_khong_duoc_no_ra() {
        // Cách sửa rẻ nhất là nhét hai trường vào ProblemResponse. Nó cũng phục vụ
        // GET /api/v1/problems/{code} — trang ai cũng mở được. Ca này chặn đúng lối tắt ấy.
        assertThat(ten(ProblemResponse.class))
                .doesNotContain("checkerEpsilon", "allowPublicSolutions");
    }

    @Test
    @DisplayName("bản soạn đề mang thêm status — trang cần biết nút nào dùng được")
    void ban_soan_de_co_trang_thai() {
        assertThat(ten(ProblemAuthoringResponse.class)).contains("status", "problemId");
    }
}
