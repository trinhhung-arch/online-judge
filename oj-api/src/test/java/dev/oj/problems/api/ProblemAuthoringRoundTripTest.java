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
        assertThat(ten(ProblemResponse.class))
                .doesNotContain("checkerEpsilon", "allowPublicSolutions");
    }

    @Test
    @DisplayName("bản soạn đề mang thêm status — trang cần biết nút nào dùng được")
    void ban_soan_de_co_trang_thai() {
        assertThat(ten(ProblemAuthoringResponse.class)).contains("status", "problemId");
    }
}
