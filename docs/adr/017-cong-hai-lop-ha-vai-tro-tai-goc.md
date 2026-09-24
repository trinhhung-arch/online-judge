# 017 · Cổng 2FA của ADMIN hạ vai trò tại gốc, không kiểm ở từng use-case

**Bối cảnh.** FR-AUTH-10: ADMIN chưa bật 2FA thì không dùng được quyền ADMIN. Bản V11 đặt
cổng trong `RequiresRoleAdvisorConfig`, và cổng chỉ được hỏi khi use-case khai
`@RequiresRole(ADMIN)`. Nhưng quyền ADMIN còn đi qua **cửa khác**: nhiều use-case khai
SETTER hoặc USER rồi trao quyền vượt chủ sở hữu bằng `isAdmin()` hoặc `:requesterRole =
'ADMIN'` trong SQL. Rà ngày 2026-09-23 đếm được **15 lời gọi trong 11 use-case** (cộng
`Problem.isVisibleTo`, không ai gọi), trong đó `DownloadTestdataUseCase` — tải testdata mọi
đề, đúng thứ `DatabaseTwoFactorGate` ghi là lý do cổng tồn tại. `AuthorizationIT.CongHaiLop` chỉ thử một use-case mang nhãn ADMIN, nên CI
xanh suốt.

---

## Lựa chọn

| | Cách | Kết quả |
|---|---|---|
| A | **Hạ vai trò ở `JwtCurrentUserProvider`**: ADMIN chưa bật 2FA thành SETTER, kèm cờ `adminChuaBatHaiLop` | Mọi `isAdmin()`, mọi `role()` vào SQL — kể cả chỗ viết sau này — nhận vai trò đã hạ |
| B | Thêm lời hỏi cổng vào từng chỗ `isAdmin()` | Vá được hôm nay. Chỗ thứ 16 viết sau này quên, và mở lại đúng đường vòng ấy |
| C | Không phát token ADMIN cho tới khi bật 2FA (hạ ở `LoginUseCase`) | Token sống 15 phút: tắt 2FA xong vẫn giữ ADMIN 15 phút — đúng lý do `TwoFactorGate` không nằm trong claim |

**Quyết định: A**, cộng phần tốt nhất của B: cả 11 use-case đều được **ghim bằng test** — xem
Hệ quả.

---

## Lý do

**Chốt phải nằm ở nơi mọi quyền đi qua, không phải nơi phần lớn quyền đi qua.** Mọi use-case
đọc danh tính qua `CurrentUserProvider` — đó là seam duy nhất. `@RequiresRole` thì chỉ là
*sàn* (javadoc của chính nó ghi thế), và quyền vượt chủ sở hữu cố ý không nằm ở sàn.

**SETTER, không phải USER.** Người ấy vẫn soạn được đề *của chính mình* — việc SETTER nào cũng
làm. Chỉ quyền vượt qua chủ sở hữu là mất. Hạ xuống USER là phạt thêm một thứ không liên quan
tới lý do bị hạ.

**Giữ mã `auth.can_hai_lop`.** Nếu chỉ hạ vai trò, endpoint ADMIN sẽ trả `auth.thieu_quyen`, và
một ADMIN thật sẽ đi xin cấp lại vai trò họ đang có. Cờ `adminChuaBatHaiLop` tồn tại **chỉ** để
advisor nói đúng lý do; nó không cấp quyền gì.

**Một lượt đọc mỗi request, chỉ với token ADMIN.** Kết quả ghi lại vào `CurrentUserHolder`, nên
advisor, use-case và use-case lồng nhau không hỏi lại. Advisor không còn hỏi cổng lần hai.

---

## Hệ quả chấp nhận

- Mọi request mang token ADMIN tốn một lượt đọc `user_two_factor` — kể cả trang công khai. Token
  ADMIN là thứ hiếm nhất hệ thống.
- DB chết thì request của ADMIN hỏng ngay ở `current()` thay vì ở use-case ADMIN đầu tiên. DB
  chết thì mọi thứ khác cũng đã hỏng (ADR 005).
- **11 use-case được ghim** bằng 12 ca: `HaiLopKhongDiVongIT` (testdata qua HTTP · bài nộp,
  cả hai câu SQL · đề: mở, sửa, gỡ xuống, xoá, nạp testdata · job: xem, liệt kê, huỷ) và
  `HaiLopKhongDiVongKyThiIT` (đề trước giờ mở · danh sách đề · trang kỳ thi · bảng đóng băng).
  Mỗi ca: chưa bật → chặn, bật lại → cùng lời gọi ấy qua. Đo 2026-09-23 trên
  `AuthorizationIT` + hai lớp trên: gỡ phép hạ vai trò → **13/24 đỏ** (12 ca mới + ca
  `can_hai_lop` cũ); trả lại → 24/24 xanh.
- `JwtService.doc()` thành package-private: vai trò **thô** trong token chỉ còn
  `JwtAuthFilter` đọc được. Trình biên dịch — không phải một luật có thể bị nới — chặn đường
  vòng qua cổng bằng cách tự giải mã token.
- Chưa canh: một use-case đọc `users.role` thẳng từ DB để quyết định quyền. Hôm nay không có
  chỗ nào (rà 2026-09-23); ai viết chỗ đầu tiên thì phải đi qua `CurrentUserProvider`.
