package dev.oj.contract;

import java.util.List;

/**
 * Mô tả một nhóm test — <b>đủ để worker tự tính điểm nhóm</b>, và không hơn.
 *
 * <h2>Vì sao worker cần biết những thứ này</h2>
 * {@link JudgeResultDto} đã đòi worker trả về {@code List<SubtaskResultDto>} kèm
 * {@code maxScore} của từng nhóm. Trước bản này, hợp đồng không mang điểm nhóm, không mang
 * {@code MIN/SUM}, không mang phụ thuộc — tức là worker được yêu cầu tính một thứ nó không
 * có đầu vào. Đây là chiều đi của cùng một phép tính mà chiều về đã tồn tại từ đầu.
 *
 * <p>Vẫn <b>không</b> có gì mang nội dung testcase ở đây. Test nào thuộc nhóm nào thì
 * {@link TestcaseMetaDto#subtaskOrdinal()} nói, và nó cũng chỉ mang số thứ tự.
 *
 * @param ordinal   số thứ tự nhóm, 1..100 — khớp {@code subtasks.ordinal}
 * @param points    điểm tối đa của nhóm. Tổng {@code points} của mọi nhóm <b>phải</b> bằng
 *                  {@code JudgeJobDto.maxScore}; hợp đồng kiểm điều đó, vì lệch nghĩa là bảng
 *                  xếp hạng sai mà không ai chứng minh được
 * @param scoring   {@code MIN} (sai một test mất cả nhóm) hoặc {@code SUM}
 * @param dependsOn các nhóm phải đạt <b>trọn điểm</b> trước, nếu không nhóm này bị bỏ qua
 *                  (FR-PROB-06). Chỉ được trỏ tới nhóm có {@code ordinal} NHỎ HƠN — xem
 *                  javadoc của trường
 */
public record SubtaskSpecDto(
        int ordinal,
        int points,
        SubtaskScoring scoring,
        List<Integer> dependsOn) {

    /** Khớp {@code CHECK (ordinal BETWEEN 1 AND 100)} của {@code subtasks}. */
    public static final int MAX_ORDINAL = 100;

    public SubtaskSpecDto {
        ContractChecks.requireRange(ordinal, 1, MAX_ORDINAL, "ordinal");
        ContractChecks.requireAtLeast(points, 0, "points");
        if (scoring == null) {
            throw new NullPointerException("scoring");
        }
        dependsOn = ContractChecks.frozen(dependsOn);
        for (Integer dependency : dependsOn) {
            if (dependency == null) {
                throw new NullPointerException("dependsOn chứa null");
            }
            // ★ Chỉ cho phụ thuộc NGƯỢC. Đây là cách rẻ nhất để loại vòng lặp: một đồ thị
            // mà mọi cạnh đều đi từ số lớn về số nhỏ thì không thể có chu trình, nên
            // SubtaskScorer duyệt một lượt theo thứ tự tăng dần là xong — không cần sắp xếp
            // tô-pô, không cần phát hiện chu trình, không có nhánh nào để sai.
            //
            // Cái giá: không diễn đạt được "nhóm 1 phụ thuộc nhóm 5". Chưa đề nào cần thế,
            // và nếu cần thì đánh số lại nhóm là xong.
            if (dependency >= ordinal) {
                throw new IllegalArgumentException(
                        "Nhóm " + ordinal + " phụ thuộc nhóm " + dependency
                                + " — chỉ được phụ thuộc nhóm có số thứ tự NHỎ HƠN, để đồ thị "
                                + "phụ thuộc không thể có chu trình");
            }
            ContractChecks.requireRange(dependency, 1, MAX_ORDINAL, "dependsOn");
        }
    }

    public boolean hasDependencies() {
        return !dependsOn.isEmpty();
    }

    /** Nhóm không phụ thuộc gì, tính điểm kiểu IOI cổ điển. */
    public static SubtaskSpecDto of(int ordinal, int points) {
        return new SubtaskSpecDto(ordinal, points, SubtaskScoring.MIN, List.of());
    }
}
