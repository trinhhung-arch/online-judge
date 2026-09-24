# 016 · Xác minh email ở mức mềm — một cái nhãn, không phải một cánh cổng

**Bối cảnh.** FR-AUTH-09 (*quên mật khẩu qua email*) được đánh **Won't — hoãn v1.1** ngay từ
bản đặc tả đầu, lý do ghi trong `frplan.md`: *"cần SMTP, thêm một điểm hỏng, không đáng cho
v1.0"*. Ghi chú dưới FR-AUTH-01 gộp luôn việc **xác minh email** vào đó, và nói rõ hai thứ ấy
phục vụ hai mục tiêu khác nhau:

- Chặn **bot tạo tài khoản hàng loạt** → Turnstile làm, xác minh email làm rất kém (hộp thư
  dùng-một-lần có hàng nghìn tên miền, một script lấy hộp thư tạm mất vài trăm mili giây).
- Có một **kênh liên lạc đã xác minh** → chỉ xác minh email làm được, và đó là điều kiện để
  sau này làm quên-mật-khẩu mà không biến nó thành một cửa chiếm tài khoản.

M0–M6 đã xong (README), nên lý do hoãn — *"không đáng cho v1.0"* — hết hiệu lực: không còn mốc
nào bị cắt thời gian. Câu hỏi còn lại không phải *"có làm không"* mà là *"chưa xác minh thì bị
chặn cái gì"*, và đó mới là chỗ quyết định thật.

---

## Lựa chọn

| | Mức | Kết quả |
|---|---|---|
| A | **Mềm — chỉ gắn nhãn `users.email_verified_at`, không chặn gì** | SMTP chết không ai mất quyền vào hệ thống. Đủ để mở FR-AUTH-09 sau này |
| B | Chặn đăng nhập cho tới khi xác minh | Đúng kiểu quen thuộc. **Nhà cung cấp thư chết vào ngày contest = người vừa đăng ký không vào thi được** |
| C | Chặn đăng ký dự thi (đăng nhập, nộp bài vẫn được) | Trung dung. Thêm một nhánh kiểm quyền ở `contests`, và vẫn giữ nguyên rủi ro của B, chỉ nhỏ hơn |

**Quyết định: A.**

---

## Lý do

**Mức chặn phải tương xứng với mục tiêu, và mục tiêu ở đây không phải là chặn.** Theo chính
`frplan.md`, việc xác minh tồn tại để *có* một kênh liên lạc đã xác minh. Một cái nhãn làm
trọn việc đó. B và C giải một bài toán khác — bài toán chống bot — mà Turnstile đã giải, và
giải tốt hơn.

**Mức mềm là điều kiện để thêm SMTP vào hệ thống này, không phải một sự nửa vời.** Lý do gốc
để hoãn FR-AUTH-09 là *"thêm một điểm hỏng"*, và lý do ấy đúng. Mức mềm không xoá điểm hỏng
ấy đi — nó làm cho điểm hỏng đó **không nằm trên đường nào quan trọng**:

| Thành phần chết | Với mức mềm | Với mức chặn |
|---|---|---|
| SMTP | hôm ấy không ai xác minh được | người vừa đăng ký không đăng nhập được |
| SMTP, đúng ngày contest | không ai để ý | một lớp thí sinh bị khoá ngoài kỳ thi |

Cột bên phải là một sự cố **truy cập** gây ra bởi một dịch vụ ta không điều khiển được. Với
một hệ thống mà thứ hạng thứ hai trong ba điều không thể thoả hiệp là *không mất bài nộp*,
đổi lấy một hàng rào chống bot mà Turnstile đã lo là một cuộc đổi chác lỗ.

**Đối xứng với hai hàng rào đã có, và ngược chiều với chúng một cách có chủ ý.**
`RedisRegistrationRateLimiter` và `TurnstileVerifier` đều **hỏng thì TỪ CHỐI** — vì thứ chúng
chặn là việc *tạo tài khoản mới*, và hoãn việc đó vài phút không làm ai mất gì. Ở đây thì
ngược: thứ bị ảnh hưởng là những người **đã có tài khoản**, nên hỏng phải **cho qua**. Cùng
một câu hỏi — *"hỏng thì nghiêng về phía nào"* — cho hai câu trả lời khác nhau, vì hai bên
cân không giống nhau.

**Mã 6 chữ số, không phải link.** Link cần một trang landing và một request `GET` làm đổi
trạng thái. Mã gõ tay đi bằng `POST` như mọi thao tác ghi khác, và lá thư không có link nào
cũng là lá thư không dạy người dùng bấm vào link trong thư tự xưng là của hệ thống.

**Endpoint gửi lại đòi đăng nhập.** Một cửa phát thư ra ngoài gọi được khi chưa đăng nhập thì
phải có bộ đếm theo IP riêng, và phải trả lời giống hệt nhau cho địa chỉ có thật lẫn không có
thật — nếu không, nó là máy dò danh sách người dùng. Đòi đăng nhập xoá cả hai vấn đề, và
không mất gì, **chính vì** mức mềm không chặn đăng nhập.

---

## Hệ quả chấp nhận

**Một dependency mới:** `spring-boot-starter-mail`. Đường không cần dependency nào — gọi HTTP
API của một nhà cung cấp (Resend, Brevo, SES) qua `RestClient` như `TurnstileVerifier` — bị bỏ
qua có chủ ý: SMTP chạy được với bất kỳ máy chủ thư nào, kể cả một hộp thư Gmail thường, nên
không khoá dự án vào một nhà cung cấp.

**Cửa đăng ký giờ có hai lời gọi ra ngoài** (Turnstile, rồi SMTP), nên nó chậm hơn. Chấp nhận
được vì đăng ký không nằm trên đường `nộp bài → verdict` — ngân sách 2 giây của `nfrplan.md`
2.1 không tính nó. Nhưng **cả hai lời gọi phải có trần thời gian**: mặc định của JavaMail là
chờ vô hạn, nên `SmtpEmailSender` đọc lại ba thuộc tính timeout trên chính đối tượng gửi thư
và từ chối khởi động nếu thiếu.

**Tài khoản cũ giữ `email_verified_at = NULL`** — V13 cố ý không backfill. Chúng chưa từng xác
minh, và đánh dấu ngược lại là ghi một điều không đúng vào database để bảng trông đẹp. Ngày
nào chuyển sang mức chặn thì câu hỏi *"tài khoản cũ thì sao"* phải được trả lời tường minh
trong một migration riêng, chứ không thừa hưởng im lặng từ đây.

**Mã 6 chữ số chỉ an toàn nhờ ba hàng rào quanh nó**, không nhờ độ dài: trần 5 lần thử · hạn
30 phút · mã mới huỷ mã cũ. Bỏ một trong ba thì **mọi test chức năng vẫn xanh** trong khi độ
khó của việc dò sụp xuống. Đó là lý do mỗi hàng rào có một ca test nói thẳng về nó, và
`EmailVerificationProperties` crash lúc boot nếu ai đó nới quá xa.

**Nếu sau này đổi sang mức chặn**, endpoint gửi lại phải được nghĩ lại từ đầu: lúc ấy
*"đăng nhập rồi mới xin được mã"* trở thành một vòng lặp không lối ra. Đó là một ADR khác, và
nó thay thế cái này chứ không sửa nó.
