# 015 · Thứ tự đề trong kỳ thi chính là cái nhãn — bỏ cột `ordinal`

**Bối cảnh.** V7 cho `contest_problems` hai cột cùng nói một chuyện: `label` (`'A'`, `'B'`) là
thứ thí sinh nhìn thấy, `ordinal` (số) là thứ dùng để `ORDER BY`. Người gọi API truyền cả hai
và tự giữ cho chúng khớp nhau.

Hai cột ấy hỏng theo hai tầng, và tầng dưới mới là tầng phải sửa:

- **Khó hiểu.** Đặt được `label='D', ordinal=1` cạnh `label='A', ordinal=2`. Lúc đó "bài A"
  không phải bài đầu tiên, và không cột nào sai cả — cả hai đều hợp lệ. Người đọc code không
  có cách nào biết cột nào mới là thứ tự thật.
- **Hỏng thật.** `label` có `UNIQUE (contest_id, label)`; `ordinal` **không có ràng buộc nào**.
  Câu liệt kê là `ORDER BY cp.ordinal` trần, không tiebreaker. Hai đề cùng `ordinal` thì SQL
  không hứa gì về thứ tự giữa chúng — thứ tự đổi được khi plan đổi hoặc khi một dòng bị
  `UPDATE`. Giữa kỳ thi, "bài B" đổi nghĩa giữa hai lần tải trang. Đó là chuyện **công bằng**
  (bất biến #1), không phải chuyện giao diện.

Dữ liệu dev đã có sẵn đúng một cặp như thế trước khi V12 chạy: kỳ thi 6 có hai đề, nhãn `A` và
`D`, cùng `ordinal = 1`.

---

## Lựa chọn

| | Cách | Kết quả |
|---|---|---|
| A | Thêm `UNIQUE (contest_id, ordinal)` + tiebreaker `ORDER BY ordinal, label` | Hết bất định. **Vẫn còn hai cột cãi nhau được** — `label='D'` vẫn đứng trước `label='A'` |
| B | **Bỏ `ordinal`, sắp bằng `ORDER BY length(label), label`** | Hết cả bất định lẫn mâu thuẫn. Mất khả năng đổi thứ tự mà không đổi nhãn |
| C | Giữ `ordinal` nhưng suy ra từ `label` bằng `CHECK` | Đúng, nhưng là một biểu thức `ascii()` dài để giữ một cột không còn mang thông tin gì |

**Quyết định: B.**

---

## Lý do

**Nhãn đã đủ sức làm thứ tự một mình.** Nó không phải chuỗi tự do:
`AuthorContestUseCase.NHAN` ép nó về `^[A-Z]{1,2}$`, và `UNIQUE (contest_id, label)` cấm trùng
trong cùng kỳ thi. Hai điều đó cộng lại cho một thứ tự **toàn phần và xác định**:

```sql
ORDER BY length(label), label      -->  A, B, ..., Z, AA, AB, ...
```

`length` đứng trước là phần bắt buộc, không phải trang trí: sắp theo chữ cái trần thì
`'AA' < 'B'`, tức AA chen vào giữa A và B — sai với thứ tự ICPC.

**A chữa triệu chứng, B chữa nguyên nhân.** Câu hỏi mở đầu là "vì sao đoạn này khó hiểu", và
cái khó hiểu là *hai cột cùng nói một chuyện*. A giữ nguyên đúng chỗ ấy.

**Giá của B rẻ đúng lúc này.** Client duy nhất là trang tĩnh trong
`oj-api/src/main/resources/static` (`contest.html`, `ra-de.html` và hai file JS của chúng) —
sửa trong cùng một lần, không có bên thứ hai phải chờ. `oj-web` chưa có dòng nào. Càng để lâu
càng đắt.

---

## Hệ quả chấp nhận

**Mất khả năng đổi thứ tự mà không đổi nhãn.** Với ICPC/IOI thì nhãn *chính là* thứ tự — đổi
thứ tự nghĩa là đổi nhãn — nên không mất gì thật.

**Ngày nào nhãn được nới ra chuỗi tự do thì cột thứ tự phải quay lại.** `'Bài 10'` phải đứng
sau `'Bài 9'`, mà `length` rồi chữ cái thì không cho ra điều đó. Ràng buộc này được ghi ngay
tại `AuthorContestUseCase.NHAN` để người nới regex đọc thấy trước khi nới.

**`DROP COLUMN` không lùi lại được.** Chấp nhận: dữ liệu trong cột ấy là 3 dòng ở máy dev, và
hai trong ba dòng đang mâu thuẫn với nhãn của chính chúng — không có thông tin nào để mất.

**Không đổi FR nào.** FR-CON-01 nói kỳ thi có "danh sách đề"; nó chưa từng nói danh sách ấy
được sắp bằng gì.
