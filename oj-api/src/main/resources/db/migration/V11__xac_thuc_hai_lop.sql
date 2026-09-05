-- =============================================================================
-- V11 — Xác thực hai lớp (TOTP, RFC 6238) cho tài khoản quản trị
--
-- ★ VÌ SAO LÀ BẢNG RIÊNG, KHÔNG PHẢI CỘT THÊM VÀO `users`
-- Cùng lý do `Credentials` tách khỏi `User` trong tầng domain: thứ nguy hiểm phải VẮNG MẶT
-- khỏi đối tượng mà mọi người truyền đi khắp nơi, chứ không phải có mặt và mọi người nhớ
-- đừng chạm. `SELECT * FROM users` là câu người ta gõ hàng ngày; nó không được trả về bí
-- mật TOTP của ai.
--
-- ★ BÍ MẬT ĐƯỢC MÃ HOÁ TRƯỚC KHI VÀO ĐÂY
-- `secret_enc` là AES-256-GCM, khoá đọc từ OJ_TOTP_KEY. Lộ một bản dump database KHÔNG
-- được đồng nghĩa với sinh được mã của người khác — cùng tinh thần với `refresh_tokens`
-- lưu SHA-256 thay vì token thô (V5).
--
-- ★ `last_step` LÀ CHỐNG PHÁT LẠI, KHÔNG PHẢI THỐNG KÊ
-- Một mã TOTP sống 30 giây. Không ghi lại bước đã dùng thì trong 30 giây ấy cùng một mã
-- dùng được nhiều lần — ai nhìn trộm màn hình hoặc đọc được một request cũ vẫn vào được.
-- RFC 6238 mục 5.2 nói thẳng: bên xác thực PHẢI từ chối bước đã dùng.
-- =============================================================================

CREATE TABLE user_two_factor (
    user_id      BIGINT      PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    secret_enc   TEXT        NOT NULL,
    -- false = đã sinh bí mật nhưng người dùng chưa chứng minh quét được mã QR.
    -- Hàng chưa bật KHÔNG có tác dụng gì lúc đăng nhập; nó chỉ là bản nháp.
    enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    last_step    BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at TIMESTAMPTZ,

    CONSTRAINT ck_two_factor_confirmed CHECK (enabled = FALSE OR confirmed_at IS NOT NULL)
);

-- -----------------------------------------------------------------------------
-- Mã dự phòng — thứ đứng giữa "mất điện thoại" và "mất tài khoản quản trị vĩnh viễn"
--
-- Băm bằng chính BCrypt của mật khẩu, không lưu thô: một mã dự phòng là một mật khẩu
-- dùng một lần, và nó phải được đối xử như mật khẩu.
--
-- Không xoá sau khi dùng mà đánh dấu `used_at`: người quản trị cần thấy được "mã của tôi
-- đã bị dùng lúc 3 giờ sáng" — xoá đi thì dấu vết ấy biến mất cùng với nó.
-- -----------------------------------------------------------------------------
CREATE TABLE user_scratch_code (
    id        BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id   BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    used_at   TIMESTAMPTZ
);

-- Partial: lúc đăng nhập chỉ quan tâm mã CHƯA dùng, và bảng này mỗi người tối đa 10 dòng.
CREATE INDEX ix_scratch_code_chua_dung ON user_scratch_code (user_id)
    WHERE used_at IS NULL;
