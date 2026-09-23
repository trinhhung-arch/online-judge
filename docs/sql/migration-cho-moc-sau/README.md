# Migration của các mốc sau — chưa được kích hoạt

`postgres-design.md` mục 16 gắn mỗi file vào một mốc. **Đang chạy: V1–V13 + `R__seed`.**

> ✅ `V4__subtasks_va_ket_qua_theo_nhom.sql` đã được kích hoạt ở M3 cùng với `SubtaskScorer`
> và `SubtaskSpecDto` — nó nằm ở `oj-api/src/main/resources/db/migration/` từ đó.
>
> ✅ `V5__auth_refresh_token_va_audit_log.sql` đã được kích hoạt ở M4 (Bước 4.1) cùng với
> module `identity`.
>
> ✅ `V6__jobs_nen_va_van_hanh.sql` đã được kích hoạt ở M4 cùng khung `platform/jobs` —
> đây chính là phương án (a) được thực thi.
>
> ✅ `V7__contests_va_bang_xep_hang.sql` đã được kích hoạt ở M5 (Bước 5.1) cùng module
> `contests`. Lưu ý: `build-order.md` PHẦN 7 gọi nó là "Migration V6" — đó là số **trước**
> lần đổi số ở M4, khi hạ tầng job được kéo lên. Nội dung không đổi, chỉ số hiệu.
>
> 🔀 **Job nền đã đổi số từ V7 thành V6, contest từ V6 thành V7.** `build-order.md` PHẦN 6 nêu
> một xung đột thứ tự: Bước 4.10 (upload ZIP) phải là job nền, mà hạ tầng job lại nằm ở M6.
> Phương án (a) đã được chọn — kéo hạ tầng job lên tuần 7. Flyway áp dụng theo thứ tự số
> tăng dần và **từ chối một phiên bản thấp xuất hiện sau một phiên bản cao đã chạy**, nên
> "kéo lên" bắt buộc phải kèm đổi số, không thể chỉ chuyển file. Cả hai file này chưa từng
> chạy ở đâu, nên đổi số ở đây **không** phải sửa migration đã commit theo nghĩa của
> bất biến #6 — thứ bất biến đó cấm là sửa một file Flyway **đã được áp dụng**.

Các file ở đây đã viết xong nhưng **cố ý chưa nằm trong `db/migration/`**: Flyway chạy mọi
file nó thấy, nên copy sớm là dựng bảng của tuần 12 vào tuần 2 — và từ đó không ai còn biết
schema thật đang ở đâu.

| File | Mốc | Kích hoạt cùng với |
|---|---|---|
| `V10__ai_review.sql` | tuần 14–15 | module `ai` |

> ✅ `V9__phan_quyen_role_ung_dung.sql` đã được kích hoạt ở M6 — và mang số **V8**, vì
> `de_soan_rieng_cho_ky_thi` của M5 lấy mất V10 trước đó. Bảng này từng liệt kê nó ở hàng V9.
>
> ⚠️ **Số trong tên file còn lại ở đây là số CŨ, không phải số nó sẽ mang.** `V10` đã bị
> `de_soan_rieng_cho_ky_thi` dùng; `ai-review-plan.md` thì từng ghi `V13`, và V13 cũng đã bị
> `xac_minh_email` dùng. Ba lần lỡ cho cùng một file, vì mỗi migration giao ra trong lúc chờ
> đều đẩy số kế tiếp đi một bậc.
>
> Nên **số chốt lúc `git mv`, không phải lúc viết**: nhìn
> `ls oj-api/src/main/resources/db/migration/` rồi lấy số kế tiếp còn trống (hôm nay là
> **V14**), và sửa cả dòng `-- V<n>` ở đầu file cho khớp.

**Cách kích hoạt:** `git mv` file sang `oj-api/src/main/resources/db/migration/`, chạy trên DB
rỗng **và** DB đã có dữ liệu, đo thời gian khoá (`CLAUDE.md` mục 6).

**Được sửa gì lúc chuyển, và không được sửa gì:**

| | Lúc `git mv` | Sau khi đã chạy ở đâu đó |
|---|---|---|
| Số hiệu (tên file + dòng `-- V<n>`) | ✅ bắt buộc, xem cảnh báo ở trên | ❌ đổi checksum → `flyway validate` đỏ trên mọi máy |
| Câu lệnh SQL | ❌ tạo `V<n+1>` mới | ❌ tạo `V<n+1>` mới |

Bất biến #6 cấm sửa một file Flyway **đã được áp dụng**. File ở thư mục này chưa từng chạy ở
đâu, nên đổi số của nó không thuộc diện đó — chính đoạn 🔀 ở trên đã dùng lập luận ấy một lần
khi job nền và contest hoán đổi V6/V7.
