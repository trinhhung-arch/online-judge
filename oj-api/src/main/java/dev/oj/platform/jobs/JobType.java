package dev.oj.platform.jobs;

/**
 * Năm loại job nền. Khớp đúng {@code CHECK (type IN (...))} của bảng {@code jobs} (V6).
 *
 * <h2>Vì sao một enum ở {@code platform} lại được phép kể tên việc của nghiệp vụ</h2>
 * Luật ArchUnit 3b cấm {@code platform} <b>import</b> module nghiệp vụ, và enum này không
 * import gì cả — nó là <i>từ vựng</i>, không phải phụ thuộc. Cùng một quan hệ như bảng
 * {@code jobs} nằm trong cùng một database với {@code submissions}: biết tên nhau không phải
 * là dính vào nhau.
 *
 * <p>Việc thật của mỗi loại nằm ở module sở hữu dữ liệu, sau {@link JobHandler}
 * ({@code cau-truc-source.md} mục 3.5): {@code TESTDATA_IMPORT} ở {@code problems},
 * {@code REJUDGE} ở {@code judging}, hai loại contest ở {@code contests}.
 *
 * <p><b>Thêm một loại là thêm một dòng ở đây VÀ một migration mới cho ràng buộc CHECK.</b>
 * Đó là chủ ý: một loại job không ai chạy được vì database từ chối thì hỏng ồn ào lúc tạo,
 * chứ không hỏng im lặng lúc chạy.
 */
public enum JobType {

    /** FR-ADM-01 — chấm lại hàng loạt. {@code judging} (M6). */
    REJUDGE,

    /** FR-PROB-03 — nạp ZIP testdata ≤200MB. {@code problems}, Bước 4.10. */
    TESTDATA_IMPORT,

    /** FR-CON-08 — dựng lại bảng xếp hạng từ Postgres. {@code contests} (M5). */
    LEADERBOARD_REBUILD,

    /** FR-CON-09 — đối chiếu bảng xếp hạng Redis với Postgres. {@code contests} (M5). */
    STANDINGS_DRIFT_CHECK,

    /** Hiệu chuẩn {@code host_factor}. {@code platform} — không thuộc nghiệp vụ nào. */
    HOST_BENCHMARK;

    public static JobType fromCode(String code) {
        for (JobType t : values()) {
            if (t.name().equals(code)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Loại job không hợp lệ: " + code);
    }
}
