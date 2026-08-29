# Migration của các mốc sau — chưa được kích hoạt

`postgres-design.md` mục 16 gắn mỗi file vào một mốc. **Đang chạy: V1–V6 + `R__seed`.**

> ✅ `V4__subtasks_va_ket_qua_theo_nhom.sql` đã được kích hoạt ở M3 cùng với `SubtaskScorer`
> và `SubtaskSpecDto` — nó nằm ở `oj-api/src/main/resources/db/migration/` từ đó.
>
> ✅ `V5__auth_refresh_token_va_audit_log.sql` đã được kích hoạt ở M4 (Bước 4.1) cùng với
> module `identity`.
>
> ✅ `V6__jobs_nen_va_van_hanh.sql` đã được kích hoạt ở M4 cùng khung `platform/jobs` —
> đây chính là phương án (a) được thực thi.
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
| `V7__contests_va_bang_xep_hang.sql` | M5 | `contests`, FR-CON-* |
| `V8__ai_review.sql` | tuần 14–15 | module `ai` |
| `V9__phan_quyen_role_ung_dung.sql` | M6 | GRANT/REVOKE cho `oj_app` — **hai role đã tồn tại từ M0**, xem `infra/postgres/init/01-roles.sql` |

**Cách kích hoạt:** `git mv` file sang `oj-api/src/main/resources/db/migration/`, chạy trên DB
rỗng **và** DB đã có dữ liệu, đo thời gian khoá (`CLAUDE.md` mục 6). Không sửa nội dung file
lúc chuyển — nếu cần sửa thì tạo `V<n+1>` mới (bất biến #6).
