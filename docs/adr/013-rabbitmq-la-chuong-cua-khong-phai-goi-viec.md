# 013 · RabbitMQ là chuông cửa, không phải gói việc

**Bối cảnh.** Bước 6.4 yêu cầu chuyển hàng đợi từ Postgres sang RabbitMQ: quorum queue,
`prefetch=1`, **ack tay sau khi kết quả đã vào DB**, DLQ sau 3 lần. `build-order.md` kèm một
thước đo hiếm khi được viết ra rõ đến thế: *"Nếu bạn thấy Bước 6.4 đụng vào nhiều hơn hai
file, nghĩa là ở đâu đó M1 đã làm sai."*

Câu ấy đọc được theo hai cách, và hai cách dẫn tới hai hệ thống khác nhau.

**Quyết định.** Message chứa **chỉ `submissionId`**, và worker **không dùng con số đó để chọn
bài** — nó vẫn gọi `POST /internal/judge/claim` như từ M1. Message là một tiếng chuông đánh
thức, không phải một gói việc. Về phía worker, nó thay đúng một dòng: `Thread.sleep(idlePoll)`
trở thành `JudgeDoorbell.cho(idlePoll)`.

**Phương án bị loại: nhét cả `JudgeJobDto` vào message.**

1. **`oj-contract` phải đổi.** Hợp đồng đã đóng băng giữa hai người, và đổi nó là việc phải
   hỏi người (`CLAUDE.md` mục 5.1). Bước 6.4 được duyệt với điều kiện không đổi một dòng nào
   ở đó.
2. **Mã nguồn người dùng vào ổ đĩa của broker.** `JudgeJobDto` mang source tới 64KB. Đẩy nó
   qua RabbitMQ là tạo một bản sao ở một hệ thống thứ ba — nơi không có phân quyền của ta, và
   nơi không ai nghĩ tới khi rà bất biến #9.
3. **Message sẽ cũ.** Reaper thu hồi một bài rồi lần claim kế tiếp tăng `attempt`. Một message
   mang payload sẽ mang `attempt` cũ, và worker chấm theo một ảnh chụp đã sai — kết quả bị
   khoá lạc quan từ chối, một lượt chấm mất trắng. Một tiếng chuông thì không có gì để cũ.
4. **Thứ tự ưu tiên phải chuyển sang broker.** Hôm nay nó là `ORDER BY (priority, enqueued_at)`
   trong câu claim — một dòng SQL, có index khớp chính xác. Chuyển sang broker thì nó thành
   consumer priority, và luật ưu tiên sống ở hai nơi.

**Hệ quả đã chấp nhận, và nói thẳng ra vì nó là một sai lệch so với văn bản của Bước 6.4.**
Mệnh đề *"ack tay **sau khi kết quả đã vào DB**"* không áp dụng nguyên văn: message không mang
việc, nên "kết quả của message này" không tồn tại. Ack xảy ra sau khi rung chuông.

Bảo đảm mà mệnh đề ấy nhắm tới — *worker chết thì không mất việc* — vẫn còn, và **mạnh hơn**:
nó do `judge_queue` + lease 120s + reaper cung cấp. Mất sạch cả ba hàng đợi RabbitMQ cũng
không mất một bài nộp nào. Đó chính là điều làm cho bước này rẻ, và cũng là lý do
`build-order.md` dám đặt thước đo hai file.

Ba mệnh đề còn lại giữ nguyên văn:

- **quorum queue** — và không chỉ vì bản sao: `x-delivery-limit` **chỉ có ở quorum queue**, và
  nó là cách khai báo "DLQ sau 3 lần" bằng một tham số thay vì một chuỗi retry interceptor
  phải cấu hình khớp nhau ở cả hai phía.
- **`prefetch=1`** — mặc định của Spring AMQP là 250. Với 250, một worker vừa khởi động hút
  sạch hàng đợi vào bộ đệm của nó và những worker khác thấy hàng đợi rỗng trong khi máy rảnh.
- **ack tay** — worker chết giữa lúc nhận và rung thì message được giao lại.

**Hai hàng đợi vẫn tách, nhưng vì lý do khác lý do người ta nghĩ.** `judge.live` và
`judge.rejudge` **không** quyết định bài nào được chấm trước — một tiếng chuông từ
`judge.rejudge` vẫn làm worker nhận bài live đang chờ, nếu có, vì Postgres mới là nơi xếp thứ
tự. Chúng tách ra để **đo riêng** ("rejudge đang dồn bao nhiêu") và để **chặn riêng** (ngắt
binding của rejudge lúc 2 giờ sáng không đụng tới bài nộp trực tiếp).

**Cái giá phải trả, và cách trả.** Tên hàng đợi giờ sống ở hai file cấu hình — đúng loại trùng
lặp mà `JudgeEndpoints` sinh ra để xoá bỏ, và đúng loại lỗi mà javadoc của nó mô tả: *trình
biên dịch im lặng, test hai bên vẫn xanh vì mỗi bên dùng hằng của chính mình*. Vì không được
đặt vào `oj-contract`, `HopDongVanHanhTest` đọc thẳng cả hai file `application.yml` và đỏ ngay
lúc chúng lệch nhau. Đó là cái giá của việc giữ hợp đồng đóng băng, và nó rẻ hơn một buổi tối
đi tìm lý do worker không nhận việc.

**Nhịp chờ KHÔNG bị bỏ đi.** `JudgeDoorbell.cho(idlePoll)` vẫn dậy sau `idle-poll` khi không
có tiếng chuông nào. Vế ấy không phải phần thừa — nó là toàn bộ lý do bước này an toàn: broker
chết thì worker quay về đúng hành vi M1 (`nfrplan.md` 7.2), và reaper thả một bài thì không có
tiếng chuông nào cho bài đó. **Tiếng chuông là tối ưu, nhịp chờ là bảo đảm.** Bỏ nhịp chờ đi
là biến RabbitMQ từ đường dẫn thành kho chứa, và lúc đó R1 không còn được Postgres bảo đảm nữa.
