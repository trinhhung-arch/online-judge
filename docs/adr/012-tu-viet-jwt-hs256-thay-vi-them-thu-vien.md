# 012 · Tự viết JWT HS256 bằng JDK, không thêm thư viện JOSE

**Bối cảnh.** Bước 4.5 cần access token 15 phút (FR-AUTH-02). Cách mặc định của hệ sinh thái
là thêm `spring-security-oauth2-jose` (Nimbus) hoặc `io.jsonwebtoken:jjwt`. Cả hai là dependency
mới, và thêm dependency là việc phải hỏi người (`CLAUDE.md` mục 5.2).

**Quyết định.** Viết `dev.oj.platform.security.Jwt` — khoảng 120 dòng dùng `javax.crypto.Mac`
(HMAC-SHA256), `java.util.Base64` và Jackson 3 vốn đã có. **Không thêm dependency nào.**

**Lý do.**

1. **File này không viết mật mã, nó viết một ĐỊNH DẠNG.** Phần mật mã là `Mac` của JDK. Phần
   tự viết là ba đoạn base64url nối bằng dấu chấm — và đó là toàn bộ phạm vi rủi ro.
2. **Chỗ tự viết lại an toàn hơn chỗ dùng thư viện.** Hai lớp CVE thật sự cắn người dùng JWT —
   `alg: none` và nhầm thuật toán — đều bắt đầu từ một câu: *"đọc trường `alg` của token rồi
   làm theo"*. Sự linh hoạt ấy là thứ các thư viện phải có và là thứ hệ thống này không cần.
   `Jwt.HEADER_B64` là một **hằng số**, và bước kiểm đầu tiên so nguyên văn header của token
   với hằng đó. Miễn nhiễm theo cấu trúc, không phải nhờ nhớ bật một tuỳ chọn.
3. **Thứ tự các bước là một phần của thiết kế.** Chữ ký được kiểm **trước** khi payload được
   giải mã và đưa cho Jackson. Bộ đọc JSON không bao giờ nhìn thấy một byte nào chưa được HMAC
   xác nhận — nên mọi lỗi của bộ phân tích JSON đều nằm ngoài tầm với của người không có khoá.
4. **Cùng đánh đổi dự án đã chọn ở V1**: index biểu thức `lower(handle)` thay vì extension
   CITEXT, để không thêm một thứ phải bảo trì cho một nhu cầu hẹp.

**Điều kiện để quyết định này ĐÚNG.** Nó chỉ đúng nếu những ca mà thư viện từng bị thủng đều
được kiểm. `JwtTest` có 14 ca, trong đó bốn ca đầu của nhóm *Chữ ký* chính là bốn lớp CVE:
`alg=none` · đổi thuật toán · sửa payload giữ nguyên chữ ký · ký bằng khoá khác. **Bỏ bộ test
đó đi thì ADR này không còn hiệu lực.**

**Hệ quả chấp nhận.**

- **Chỉ có HS256, một khoá, không xoay khoá.** Đủ cho một hệ thống tự vận hành với một
  instance phát token. Cần RS256 hoặc JWKS (ví dụ để một dịch vụ khác tự kiểm token) thì viết
  ADR mới và lúc đó thêm Nimbus là đúng — nhu cầu ấy thật sự cần sự linh hoạt của thư viện.
- **Thêm một claim là làm mọi token đang lưu hành hỏng** trong tối đa 15 phút, vì mapper bật
  `FAIL_ON_UNKNOWN_PROPERTIES`. Với TTL 15 phút thì đó là một cửa sổ tự đóng.
- **Access token không thu hồi được.** Đây là thuộc tính của mọi JWT không tra database, không
  phải của việc tự viết. Bù bằng refresh token có xoay vòng (`RefreshSessionUseCase`) và bằng
  trần 15 phút mà `AppProperties.Auth` crash lúc boot nếu ai đó nới ra.

**Điều đã KHÔNG chọn, và vì sao.** `spring-boot-starter-security` đầy đủ đã nằm sẵn trong
`pom.xml` dưới dạng comment. Bỏ comment ra thì có BCrypt *và* Nimbus, nhưng nó cũng bật chuỗi
filter mặc định khoá mọi endpoint bằng HTTP Basic — một lớp bảo mật thứ hai mà hệ thống này
không dùng và phải cấu hình cho nó đừng làm gì. BCrypt cuối cùng được lấy từ
`spring-security-crypto` đơn lẻ: một artifact, không transitive dependency, không auto-config.
