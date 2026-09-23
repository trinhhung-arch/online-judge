-- =============================================================================
-- V15 — Trần dò mã hai lớp THEO TÀI KHOẢN (rà soát bảo mật 2026-09-24, F2)
--
-- ★ VÌ SAO KHÔNG ĐỦ VỚI TRẦN THEO IP CỦA FR-AUTH-08
-- Mã TOTP sai có tính là một lần đăng nhập hỏng, nhưng khoá thì tính theo IP. Người đã có
-- mật khẩu ADMIN và một dải IPv6 đổi địa chỉ sau mỗi 5 lần sai là không bao giờ bị khoá:
-- 10^6 mã, 3 mã đúng ở mỗi thời điểm, ~16 lượt/giây (trần BCrypt) — khoảng 6 giờ.
-- Dải /64 (ClientIp.khoaGioiHan) đóng đường xoay trong MỘT dải; trần này đóng đường xoay
-- NHIỀU dải, vì nó đếm trên thứ kẻ dò không đổi được: tài khoản đích.
--
-- ★ KHÔNG MỞ RA CÁI NÚT "KHOÁ TÀI KHOẢN NGƯỜI KHÁC"
-- Lý do FR-AUTH-08 khoá theo IP chứ không theo tài khoản là để không ai gõ sai vào handle
-- người khác mà khoá được họ. Trần này KHÔNG phạm điều đó: chỉ tới được bước mã hai lớp khi
-- mật khẩu đã đúng. Người không có mật khẩu không chạm được bộ đếm này.
--
-- ★ NULLABLE CÓ MẶC ĐỊNH, KHÔNG NOT NULL — oj-api/CLAUDE.md §7 (thêm cột vào bảng có dữ
-- liệu). Mọi câu đọc/ghi coi NULL là 0 / "không khoá" (COALESCE trong JdbcTwoFactorRepository).
-- Quyền của oj_app đã cấp ở mức BẢNG nên phủ luôn hai cột mới.
-- =============================================================================

ALTER TABLE user_two_factor
    ADD COLUMN failed_attempts INT DEFAULT 0,
    ADD COLUMN locked_until    TIMESTAMPTZ;

COMMENT ON COLUMN user_two_factor.failed_attempts IS
    'Số mã hai lớp sai LIÊN TIẾP; mã đúng đưa về 0; chạm oj.auth.totp-max-failures thì khoá và đưa về 0';
COMMENT ON COLUMN user_two_factor.locked_until IS
    'Tới lúc này mọi mã hai lớp (kể cả mã đúng) đều bị từ chối — V15, rà soát 2026-09-24 F2';
