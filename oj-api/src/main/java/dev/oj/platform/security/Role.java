package dev.oj.platform.security;

/**
 * Ba vai trò — FR-AUTH-06. Tên hằng trùng đúng giá trị trong
 * {@code CHECK (role IN ('USER','SETTER','ADMIN'))} của bảng {@code users}.
 *
 * <p><b>Không có vai trò thứ tư.</b> Nếu một yêu cầu cần "trợ lý ADMIN" hay "giám khảo contest",
 * dừng lại và hỏi: thêm một vai trò là thêm một hàng vào ma trận hiển thị của
 * {@code frplan.md} Quy tắc 3, và mỗi ô trong hàng đó là một quyết định về rò rỉ dữ liệu.
 *
 * <h2>Thứ tự bao hàm, và giới hạn của nó</h2>
 * {@code USER < SETTER < ADMIN} theo {@link #atLeast(Role)}, dùng cho các quyền cộng dồn
 * (ADMIN làm được mọi thứ SETTER làm). Nhưng <b>đừng dùng nó cho quyền theo quyền sở hữu</b>:
 * SETTER chỉ được sửa <i>đề của chính mình</i> và xem testdata <i>của đề mình</i> — đó là một
 * câu hỏi về dữ liệu, không phải về vai trò, và nó phải nằm trong câu query của repository
 * chứ không phải trong một câu {@code if} so sánh vai trò ({@code oj-api/CLAUDE.md} mục 2).
 */
public enum Role {

    /** Nộp bài, xem đề đã xuất bản, xem bài nộp của chính mình. */
    USER,

    /** Thêm: tạo và sửa đề của mình, xem testdata của đề mình, xuất bản đề của mình. */
    SETTER,

    /**
     * Thấy mọi thứ, kể cả {@code audit_log} và bảng xếp hạng đã đóng băng.
     *
     * <p>Vẫn <b>không</b> xoá được bài nộp — đó không phải giới hạn của vai trò mà là quyền
     * ở tầng Postgres: {@code REVOKE DELETE, TRUNCATE ON submissions FROM oj_app} (V9).
     * FR-SUB-09 là một quyền hệ thống, không phải một nút bị ẩn.
     */
    ADMIN;

    /** Vai trò này bao hàm {@code other}? Dùng cho quyền cộng dồn, không dùng cho quyền sở hữu. */
    public boolean atLeast(Role other) {
        return this.ordinal() >= other.ordinal();
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    /** Có thể soạn đề (đề của chính mình — quyền sở hữu kiểm riêng trong query). */
    public boolean canAuthorProblems() {
        return atLeast(SETTER);
    }

    public static Role fromCode(String code) {
        for (Role r : values()) {
            if (r.name().equals(code)) {
                return r;
            }
        }
        throw new IllegalArgumentException("Vai trò không hợp lệ: " + code);
    }
}
