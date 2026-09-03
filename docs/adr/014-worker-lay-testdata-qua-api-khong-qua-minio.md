# 014 · Worker lấy testdata qua API, không qua MinIO

**Bối cảnh.** Cuối M6, chạy tay cả hai tiến trình lần đầu: mọi bài nộp trả `IE`.

```
TestdataUnavailableException: Không có testdata 3f11ad6b... trong kho cục bộ
/var/tmp/oj-worker/testdata-store
```

Testdata nằm đủ trong MinIO. Nhưng worker không có đường nào tới đó: hiện thực `TestdataSource`
duy nhất đọc một thư mục cục bộ mà **không gì đổ dữ liệu vào**.

Ba tài liệu nói ba điều khác nhau, và đó là lý do đoạn này bị bỏ sót:

| Tài liệu | Nói gì |
|---|---|
| `build-order.md` 2.7 | *"Nguồn xa là `TestdataSource`; **MinIO tới ở Bước 4.11**"* |
| `TestcaseMetaDto` (`oj-contract`) | *"worker dùng nó để tải nội dung **từ MinIO** về cache cục bộ"* |
| `oj-worker/CLAUDE.md` §3 | *"không **MinIO client** gọi thẳng vào hạ tầng của API"* |

Bước 4.11 đã làm — nhưng chỉ nửa phía API (`MinioTestdataStore`, dùng để **ghi** lúc nạp đề).
Nửa phía worker không ai viết, vì viết nó theo đúng câu ở `TestcaseMetaDto` là vi phạm câu ở
`oj-worker/CLAUDE.md`.

**Vì sao không test nào đỏ suốt bốn mốc.** Mọi test của `oj-worker` **tự đổ testdata vào thư
mục ấy** trước khi chạy — `IsolateJudgeRunnerIT` dựng kho bằng tay, `SandboxAttackIT` cũng vậy.
Chúng kiểm đúng thứ chúng nhắm tới (sandbox, checker, chấm điểm) và mù hoàn toàn với câu hỏi
*"dữ liệu từ đâu tới"*. Về phía API, `TestdataImportIT` dùng kho trong bộ nhớ. Hai bên gặp nhau
đúng ở chỗ không bên nào kiểm.

**Quyết định.** Thêm **endpoint thứ năm** vào `JudgeEndpoints`:

```
GET /internal/judge/testdata/{sha256}  ->  200 application/octet-stream | 404
```

API là thứ duy nhất chạm kho. Worker vẫn chỉ biết những đường dẫn liệt kê trong
`JudgeEndpoints`, không biết MinIO tồn tại, và không giữ credential của hạ tầng nào.

**Đây là lần đầu `oj-contract` được mở ra sau khi đóng băng**, và nó thuộc đúng loại việc mà
`CLAUDE.md` mục 5.1 nói phải hỏi người: đã hỏi, đã được duyệt.

**Hai phương án bị loại.**

*MinIO client cho worker* — đúng như javadoc `TestcaseMetaDto` viết, và là cách ít code nhất.
Bị loại vì nó mâu thuẫn trực tiếp với `oj-worker/CLAUDE.md` §3 và làm `WorkerHasNoDataSourceTest`
đỏ. Lý do sâu hơn con số dòng code: worker chạy **mã của người lạ**. Một máy chấm bị chiếm mà
có credential MinIO là kẻ tấn công đọc được kho testdata của **mọi đề khác**, kể cả đề của kỳ
thi tuần sau. Bất biến #1 nói nội dung testcase ẩn không rời khỏi worker; nó không nói worker
được phép với tới toàn bộ kho.

*Presigned URL trong `claim` response* — API sinh URL ngắn hạn, worker tải thẳng từ MinIO.
Worker không cần client, không cần credential dài hạn. Bị loại vì nó vẫn đổi `JudgeJobDto`
(cùng chi phí hợp đồng), thêm một địa chỉ mà worker phải biết ngoài `JudgeEndpoints`, và buộc
API phải biết kho có sinh được URL ký hay không — tức là `TestdataStore` thôi là một cổng
trừu tượng và trở thành "MinIO" ghi bằng chữ khác.

**Chi phí gần bằng không, và lý do nằm ở thiết kế đã có.** Worker cache theo hash
(`ContentAddressedCache`), và hash chỉ đổi khi đề đổi testdata. Một bộ test đi qua API **một
lần cho cả nghìn bài nộp cùng đề**. Đo thật với cache rỗng hoàn toàn: `AC` trong 2ms; bài thứ
hai cùng đề: 1ms, không tải lại. Đường này không nằm trên ngân sách 2 giây, đúng như
`benchmark`.

**Bốn lớp giữ nội dung không rò ra ngoài** — kiểm trên hệ thống đang chạy:

| Thử | Kết quả |
|---|---|
| không secret | 401 |
| sai secret | 401 |
| qua tiền tố `/api/v1` (thứ tunnel publish) | 404 |
| bằng JWT của một USER | 401 |
| đi ngang thư mục `..%2F..%2Fetc%2Fpasswd` | 400 |
| đúng secret (worker) | 200 |

Cộng thêm: khoá là **hash nội dung**, không phải id đề hay số thứ tự test — không đoán được,
không duyệt được, và biết một hash không cho biết nó thuộc đề nào.

**Một luật mới sinh ra từ đây.** `HttpSurfaceTest` giờ đối chiếu **mọi hằng trong
`JudgeEndpoints` với controller phục vụ nó**. Nó không bắt được toàn bộ lớp lỗi này — nó không
biết worker có gọi hay không — nhưng nó bắt nửa quan trọng hơn: *một đường dẫn được hứa mà
không ai phục vụ*. Đã kiểm chứng bằng cách thêm một hằng giả và xem nó đỏ.
