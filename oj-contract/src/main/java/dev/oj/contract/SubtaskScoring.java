package dev.oj.contract;

/**
 * Cách tính điểm bên trong một nhóm test. Khớp {@code CHECK (scoring IN ('MIN','SUM'))} ở V4.
 *
 * <p>Hai cách này trả lời hai câu hỏi khác nhau, và đề chọn cái nào là chọn muốn đo gì:
 */
public enum SubtaskScoring {

    /**
     * <b>Sai một test là mất cả nhóm.</b> Kiểu IOI cổ điển, và là mặc định.
     *
     * <p>Nó đo <i>lời giải cho một lớp dữ liệu</i>: nhóm 2 nói "n ≤ 1000", và bạn hoặc giải
     * được lớp đó hoặc không. Ăn nửa nhóm không có nghĩa gì — một thuật toán đúng với 60%
     * test của cùng một ràng buộc là một thuật toán sai.
     */
    MIN,

    /**
     * <b>Cộng điểm từng test đạt.</b>
     *
     * <p>Dùng cho đề chấm theo chất lượng lời giải (heuristic, tối ưu hoá) chứ không theo
     * đúng/sai — ở đó "giải được 70% test" thật sự là một kết quả tốt hơn 40%.
     */
    SUM;

    /** Giá trị lưu trong cột {@code subtasks.scoring}. */
    public String code() {
        return name();
    }

    public static SubtaskScoring fromCode(String code) {
        for (SubtaskScoring scoring : values()) {
            if (scoring.name().equals(code)) {
                return scoring;
            }
        }
        throw new IllegalArgumentException("subtasks.scoring không hợp lệ: " + code);
    }
}
