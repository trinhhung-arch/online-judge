package dev.oj.identity.api.dto;

/**
 * FR-AUTH-05 — đúng hai trường sửa được.
 *
 * <p>Danh sách này ngắn là cố ý. Xem {@code UpdateProfileUseCase}: nếu request nhận cả một
 * {@code User} rồi ghi đè nguyên dòng, thì một trường {@code "role": "ADMIN"} gửi kèm là một
 * lần tự thăng chức. Record chỉ có hai trường thì lỗ hổng đó không tồn tại được.
 *
 * @param preferredLanguageId {@code null} để xoá lựa chọn
 */
public record UpdateProfileRequest(String displayName, Short preferredLanguageId) {
}
