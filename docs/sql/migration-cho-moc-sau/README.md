# Migration của các mốc sau — chưa được kích hoạt

`postgres-design.md` mục 16 gắn mỗi file vào một mốc. **M1 chỉ chạy V1–V3 + `R__seed`.**
Các file ở đây đã viết xong nhưng **cố ý chưa nằm trong `db/migration/`**: Flyway chạy mọi
file nó thấy, nên copy sớm là dựng bảng của tuần 12 vào tuần 2 — và từ đó không ai còn biết
schema thật đang ở đâu.

| File | Mốc | Kích hoạt cùng với |
|---|---|---|
| `V4__subtasks_va_ket_qua_theo_nhom.sql` | M3 | FR-PROB-06, `SubtaskScorer` |
| `V5__auth_refresh_token_va_audit_log.sql` | M4 | `identity`, FR-AUTH-01..08 |
| `V6__contests_va_bang_xep_hang.sql` | M5 | `contests`, FR-CON-* |
| `V7__jobs_nen_va_van_hanh.sql` | M6 (hoặc tuần 7) | hạ tầng job — xem xung đột #2 của `build-order.md` |
| `V8__ai_review.sql` | tuần 14–15 | module `ai` |
| `V9__phan_quyen_role_ung_dung.sql` | M6 | GRANT/REVOKE cho `oj_app` — **hai role đã tồn tại từ M0**, xem `infra/postgres/init/01-roles.sql` |

**Cách kích hoạt:** `git mv` file sang `oj-api/src/main/resources/db/migration/`, chạy trên DB
rỗng **và** DB đã có dữ liệu, đo thời gian khoá (`CLAUDE.md` mục 6). Không sửa nội dung file
lúc chuyển — nếu cần sửa thì tạo `V<n+1>` mới (bất biến #6).
