package dev.oj.contract;

/**
 * Điểm của một nhóm test trong một lần chấm. Ánh xạ thẳng sang bảng
 * {@code judge_run_subtasks} (V4).
 *
 * <p><b>Đây là mức chi tiết sâu nhất mà kết quả chấm được lưu xuống DB.</b> Sâu hơn nữa —
 * kết quả từng test — cố ý không lưu: 1M bài x 50 test là ~50M dòng cho một dữ liệu mà
 * theo bất biến #1 không ai được phép xem ({@code postgres-design.md} mục 1, quyết định 3).
 *
 * <p>Record này <b>rỗng ở M1</b> nhưng được khai báo ngay từ tuần 1, vì contract đóng băng
 * sau đó: thêm một trường ở tuần 5 là một PR chạm cả {@code oj-api} lẫn {@code oj-worker}
 * và hai người phải dừng việc. Khai báo trước tốn 20 dòng; mở lại contract tốn nửa ngày
 * của cả hai ({@code docs/build-order.md} Phần 1 nguyên tắc 4).
 *
 * @param subtaskOrdinal     số thứ tự nhóm, khớp {@code subtasks.ordinal}
 * @param verdict            kết quả của nhóm. {@code SKIPPED} khi nhóm phụ thuộc chưa đạt
 *                           nên không được chấm — biểu diễn bằng {@code verdict == null}
 *                           ở đây và ánh xạ sang chuỗi {@code 'SKIPPED'} ở tầng repository,
 *                           vì {@code SKIPPED} không phải một trong bảy verdict thật
 * @param score              điểm đạt được của nhóm
 * @param maxScore           {@code subtasks.points}
 * @param failedTestOrdinal  test đầu tiên làm hỏng nhóm, hoặc {@code null}
 * @param timeMs             CPU time lớn nhất trong nhóm
 * @param memoryKb           bộ nhớ lớn nhất trong nhóm
 */
public record SubtaskResultDto(
        int subtaskOrdinal,
        Verdict verdict,
        int score,
        int maxScore,
        Integer failedTestOrdinal,
        Integer timeMs,
        Integer memoryKb) {

    public SubtaskResultDto {
        ContractChecks.requireRange(subtaskOrdinal, 1, 100, "subtaskOrdinal");
        ContractChecks.requireAtLeast(score, 0, "score");
        ContractChecks.requireAtLeast(maxScore, 0, "maxScore");
        if (score > maxScore) {
            throw new IllegalArgumentException(
                    "score (" + score + ") > maxScore (" + maxScore + ") ở subtask " + subtaskOrdinal);
        }
        ContractChecks.requireNullOrRange(
                failedTestOrdinal, 1, TestcaseMetaDto.MAX_ORDINAL, "failedTestOrdinal");
    }

    /** Nhóm bị bỏ qua vì phụ thuộc chưa đạt (FR-PROB-06). */
    public boolean isSkipped() {
        return verdict == null;
    }

    /** Nhóm bị bỏ qua: 0 điểm, không có số đo. */
    public static SubtaskResultDto skipped(int subtaskOrdinal, int maxScore) {
        return new SubtaskResultDto(subtaskOrdinal, null, 0, maxScore, null, null, null);
    }
}
