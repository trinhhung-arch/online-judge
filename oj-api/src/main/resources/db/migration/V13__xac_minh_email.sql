-- =============================================================================
-- V13 — Xác minh email (FR-AUTH-09, phần "kênh liên lạc đã xác minh")
--
-- ★ MỨC MỀM: CỘT NÀY KHÔNG CHẶN GÌ CẢ
-- `email_verified_at` là một NHÃN, không phải một cổng. Chưa xác minh thì vẫn đăng nhập
-- được, vẫn nộp bài được, vẫn dự thi được. Lý do nằm ở ADR 016: mục tiêu của việc xác minh
-- là CÓ MỘT KÊNH LIÊN LẠC ĐÃ XÁC MINH (để sau này làm quên-mật-khẩu), chứ không phải chặn
-- bot — việc chặn bot là của Cloudflare Turnstile (FR-AUTH-01), và xác minh email làm việc
-- đó rất kém vì hộp thư dùng-một-lần có hàng nghìn tên miền.
--
-- Hệ quả trực tiếp: nhà cung cấp SMTP chết KHÔNG làm ai mất quyền vào hệ thống. Đó chính là
-- điều kiện để thêm một phụ thuộc ngoài vào mà không hạ availability (nfrplan Phần 7).
--
-- ★ CỐ Ý KHÔNG BACKFILL
-- Tài khoản đã có trước migration này giữ `email_verified_at = NULL`, tức là "chưa xác
-- minh" — và đó là sự thật, họ chưa từng xác minh. Đặt `= created_at` cho chúng là ghi một
-- điều không đúng vào database để cho bảng trông đẹp.
--
-- Backfill chỉ cần thiết khi cột này CHẶN thứ gì đó; ngày nào chuyển sang mức chặn thì đó
-- là một migration riêng, một quyết định riêng, và lúc ấy câu hỏi "tài khoản cũ thì sao"
-- phải được trả lời tường minh chứ không thừa hưởng im lặng từ đây.
-- =============================================================================

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

COMMENT ON COLUMN users.email_verified_at IS
    'NULL = chưa xác minh. Mức mềm: KHÔNG chặn đăng nhập/nộp bài/dự thi (ADR 016).';

-- -----------------------------------------------------------------------------
-- ★ `ck_users_anonymized` phải biết tới cột mới, nếu không FR-AUTH-07 bị hở.
--
-- Ràng buộc gốc của V1 nói: đã ẩn danh hoá thì email và băm mật khẩu phải NULL. Thêm một
-- cột nói VỀ email mà không đưa vào ràng buộc ấy là để lại một dòng "email đã xác minh lúc
-- 10:03 ngày 5/9" trên một tài khoản mà hệ thống vừa hứa là đã xoá sạch dữ liệu định danh.
-- Bản thân dấu thời gian không phải email, nhưng nó là một khẳng định về một email — và
-- FR-AUTH-07 hứa xoá, không hứa xoá một nửa.
--
-- DROP rồi ADD chứ không ALTER: Postgres không sửa tại chỗ một CHECK. Câu ADD chạy được
-- trên dữ liệu đang có vì cột vừa thêm ở trên còn NULL ở mọi dòng — kể cả dòng đã ANONYMIZED.
-- `MigrationTrenDuLieuCoSanIT` dựng đúng tình huống đó rồi chạy migration này lên trên.
-- -----------------------------------------------------------------------------
ALTER TABLE users DROP CONSTRAINT ck_users_anonymized;
ALTER TABLE users ADD CONSTRAINT ck_users_anonymized CHECK (
    status <> 'ANONYMIZED'
        OR (email IS NULL AND password_hash IS NULL AND email_verified_at IS NULL)
);

-- -----------------------------------------------------------------------------
-- email_verifications — mã xác minh đang sống.
--
-- ★ LƯU SHA-256, KHÔNG LƯU MÃ — cùng lập luận với `refresh_tokens` của V5.
-- Một mã xác minh là thông tin xác thực dùng một lần: ai đọc được nó thì xác minh hộ được
-- người khác. Lộ một bản dump database không được đồng nghĩa với việc đó.
--
-- ★ VÌ SAO MÃ 6 CHỮ SỐ MÀ VẪN AN TOÀN, VÀ ĐIỀU KIỆN ĐỂ NÓ AN TOÀN
-- Một triệu khả năng là quá ít nếu đoán được thoải mái. Ba thứ dưới đây mới làm nó đủ, và
-- thiếu MỘT trong ba là hỏng cả ba:
--   1. `attempts` — sai quá ngưỡng thì mã chết, không phải "chờ rồi thử tiếp"
--   2. `expires_at` — mã sống 30 phút, nên cửa sổ đoán là hữu hạn
--   3. mã mới HUỶ mã cũ (xem unique index dưới) — nếu không thì gửi lại 1 000 lần là có
--      1 000 mã cùng sống, và xác suất đoán trúng tăng 1 000 lần cho cùng một lượt đoán
--
-- Chọn 6 chữ số chứ không phải một token 32 byte vì người dùng phải GÕ LẠI nó. Một đường
-- link bấm được thì cần một trang landing và một request GET làm đổi trạng thái; mã gõ tay
-- đi bằng POST như mọi thao tác ghi khác.
--
-- ★ KHÔNG LƯU ĐỊA CHỈ EMAIL Ở ĐÂY
-- Bảng này chỉ có `user_id`. Địa chỉ đọc từ `users.email` lúc gửi. Chép địa chỉ sang đây là
-- tạo bản sao thứ hai của một dữ liệu định danh mà FR-AUTH-07 hứa xoá được — và bản sao ấy
-- nằm ngoài tầm với của `ck_users_anonymized`.
-- -----------------------------------------------------------------------------
CREATE TABLE email_verifications (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_sha256 CHAR(64)    NOT NULL,
    attempts    SMALLINT    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ NOT NULL,
    -- Đặt lúc mã được dùng đúng, HOẶC lúc nó bị khai tử (sai quá nhiều lần, hoặc bị một mã
    -- mới thay). Một cột cho cả hai vì phía đọc chỉ hỏi đúng một câu: "mã này còn sống không".
    consumed_at TIMESTAMPTZ,

    CONSTRAINT ck_email_verifications_window CHECK (expires_at > created_at)
);

-- ★ MỘT NGƯỜI, MỘT MÃ SỐNG. Đây là điều kiện thứ 3 ở trên, viết thành ràng buộc chứ không
-- thành một câu `if` ở tầng Java: gửi lại phải HUỶ mã cũ trước khi chèn mã mới, và nếu một
-- đường ghi nào đó sau này quên bước huỷ thì database từ chối, chứ không lặng lẽ tích tụ
-- thêm một mã đoán được.
--
-- Nó cũng chặn luôn cú double-click trên nút "gửi lại".
CREATE UNIQUE INDEX ux_email_verifications_song ON email_verifications (user_id)
    WHERE consumed_at IS NULL;

COMMENT ON TABLE email_verifications IS
    'Mã xác minh email, lưu SHA-256. Không bao giờ log cột code_sha256 hay mã thô (bất biến #9).';
