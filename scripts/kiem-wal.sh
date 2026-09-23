#!/usr/bin/env bash
# =============================================================================
# Canh WAL archiving — R4. Thoát khác 0 là có việc phải làm.
#
# ★ VÌ SAO SCRIPT NÀY QUAN TRỌNG HƠN NÓ TRÔNG
#
# WAL archiving có một kiểu hỏng mà không tính năng sao lưu nào khác có: **hỏng sao lưu
# làm SẬP DATABASE**. Khi archive_command thất bại, Postgres KHÔNG bỏ qua segment ấy —
# nó giữ lại, và giữ luôn mọi segment sau đó, chờ lưu được. pg_wal phình lên cho tới khi
# đầy đĩa, và lúc đầy đĩa thì Postgres dừng nhận ghi.
#
# Nghĩa là một thư mục sai quyền — một dòng chmod thiếu — biến một cải tiến sao lưu thành
# một sự cố mất dịch vụ toàn phần, sau vài giờ, không có cảnh báo nào ở giữa.
#
# Đó chính là câu cuối của CLAUDE.md: "phần lớn tính năng nghe hợp lý nhất trong một
# Online Judge lại chính là tính năng phá hoại nó".
#
# Chạy tay sau khi bật, và cắm vào bảng vận hành.
# =============================================================================
set -uo pipefail

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}
# Thiếu -d thì psql tìm database trùng tên user và chết — xem sao-luu-goc.sh.
DB=${OJ_DB_NAME:-ojdb}
WAL_DIR=${OJ_WAL_ARCHIVE_DIR:-$HOME/oj-backup/wal}
# archive_timeout=60s, nên quá 5 phút không lưu được segment nào là bất thường, không phải
# "đang rảnh".
TRE_TOI_DA_PHUT=${OJ_WAL_LAG_MAX_MIN:-5}
# pg_wal bình thường xoay quanh max_wal_size. Vượt ngưỡng này nghĩa là đang TÍCH TỤ.
PG_WAL_TRAN_MB=${OJ_PG_WAL_MAX_MB:-2048}

loi()   { echo "✗ $*" >&2; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

hong=0

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    loi "Container $CONTAINER không chạy."
    exit 1
fi

psql() { docker exec "$CONTAINER" psql -U "$NGUOI_DUNG" -d "$DB" -tAc "$1" 2>/dev/null; }

# ---- 1 · Bật chưa -----------------------------------------------------------
che_do=$(psql "SHOW archive_mode")
if [ "$che_do" != "on" ]; then
    loi "archive_mode = '${che_do:-?}'. WAL KHÔNG được lưu — R4 vẫn là 15 phút của pg_dump."
    luu_y "Đây là tham số mức postmaster: sửa docker-compose.yml rồi 'docker compose up -d postgres'."
    exit 1
fi
ok "archive_mode = on · archive_timeout = $(psql "SHOW archive_timeout")"

# ---- 2 · Thư mục đích có ghi được không (nguyên nhân số 1 của sự cố) ---------
# Kiểm TỪ TRONG container, không từ host: host thấy thư mục của mình, container chạy bằng
# uid 70. Hai góc nhìn khác nhau, và chỉ góc nhìn của container mới quyết định.
# ⚠️ PHẢI có -u postgres. `docker exec` mặc định chạy bằng ROOT, và root ghi được vào một
# thư mục 555 mà postmaster thì không — đo thật 2026-09-21: bản đầu của phép kiểm này in
# "✓ ghi được" trong khi pg_stat_archiver đang đếm 3 lượt thất bại. Một phép kiểm báo xanh
# sai còn tệ hơn không có phép kiểm.
if ! docker exec -u postgres "$CONTAINER" test -w /wal-archive; then
    loi "/wal-archive KHÔNG ghi được bởi user postgres (root thì ghi được — đừng tin root)."
    luu_y "pg_wal sẽ phình cho tới khi đầy đĩa. Sửa NGAY:"
    luu_y "    chmod 777 \"$WAL_DIR\"    (hoặc chown 70:70 nếu muốn chặt hơn)"
    hong=1
else
    ok "/wal-archive ghi được từ trong container."
fi

# ---- 3 · pg_stat_archiver: đã hỏng lần nào chưa -----------------------------
# Cột cuối — `dang_hong` — là cột quan trọng nhất: lượt THẤT BẠI gần nhất có mới hơn lượt
# THÀNH CÔNG gần nhất không. failed_count > 0 chỉ nói "đã từng hỏng"; nó vẫn > 0 mãi mãi sau
# một sự cố đã xử lý xong. Chỉ phép so hai mốc thời gian mới trả lời "CÓ ĐANG hỏng không".
doc=$(psql "SELECT archived_count, failed_count, coalesce(last_failed_wal,'-'),
                   coalesce(to_char(last_failed_time,'YYYY-MM-DD HH24:MI:SS'),'-'),
                   coalesce(extract(epoch from now()-last_archived_time)::bigint, -1),
                   coalesce(last_failed_time > last_archived_time, last_failed_time IS NOT NULL)
            FROM pg_stat_archiver")
IFS='|' read -r da_luu da_hong wal_hong luc_hong tre_giay dang_hong <<< "$doc"

echo "  đã lưu: ${da_luu:-?} segment · hỏng: ${da_hong:-?}"

if [ "$dang_hong" = "t" ]; then
    loi "ĐANG HỎNG NGAY LÚC NÀY — lượt thất bại ($luc_hong, $wal_hong) mới hơn lượt thành công."
    luu_y "pg_wal ĐANG tích tụ. Đây là đường dẫn tới 'đĩa đầy, database dừng'."
    luu_y "Nguyên nhân thường gặp nhất: quyền thư mục đích. Xem phép kiểm phía trên."
    hong=1
elif [ "${da_hong:-0}" -gt 0 ]; then
    luu_y "Đã từng hỏng $da_hong lượt (gần nhất $luc_hong), nhưng lượt mới nhất đã lưu được."
    luu_y "failed_count không tự về 0 — nó chỉ về 0 khi pg_stat_reset_shared('archiver')."
fi

if [ "${tre_giay:--1}" -lt 0 ]; then
    luu_y "Chưa lưu được segment nào. Bình thường nếu vừa bật — chạy lại sau ${TRE_TOI_DA_PHUT} phút."
else
    tre_phut=$(( tre_giay / 60 ))
    if [ "$tre_phut" -gt "$TRE_TOI_DA_PHUT" ]; then
        loi "Segment gần nhất lưu cách đây $tre_phut phút, trần là $TRE_TOI_DA_PHUT."
        hong=1
    elif [ "$dang_hong" = "t" ]; then
        # Mốc thành công gần nhất vẫn mới, nhưng sau nó đã có lượt hỏng. Nói "đúng cam kết"
        # ở đây là trấn an sai đúng lúc không được phép trấn an.
        luu_y "Lượt thành công gần nhất cách đây $tre_phut phút — nhưng SAU nó đã có lượt hỏng."
    else
        ok "Segment gần nhất lưu cách đây $tre_phut phút — RPO đang đúng cam kết."
    fi
fi

# ---- 4 · pg_wal có đang phình không (dự báo sự cố) --------------------------
mb=$(docker exec "$CONTAINER" sh -c "du -sm /var/lib/postgresql/data/pg_wal 2>/dev/null | cut -f1")
if [ -n "$mb" ] && [ "$mb" -gt "$PG_WAL_TRAN_MB" ]; then
    loi "pg_wal đang là ${mb}MB, vượt trần ${PG_WAL_TRAN_MB}MB — WAL ĐANG TÍCH TỤ."
    luu_y "Đây là đường dẫn tới 'đĩa đầy, database dừng'. Xử lý lỗi archive TRƯỚC KHI đĩa đầy."
    hong=1
else
    ok "pg_wal = ${mb:-?}MB (trần ${PG_WAL_TRAN_MB}MB)."
fi

# ---- 5 · Có base backup để replay lên không ---------------------------------
# WAL một mình không khôi phục được gì. Xem đầu scripts/sao-luu-goc.sh.
if ! OJ_WAL_ARCHIVE_DIR="$WAL_DIR" "$(dirname "$0")/sao-luu-goc.sh" --kiem >/dev/null 2>&1; then
    loi "Không có base backup hợp lệ — WAL đang được lưu nhưng KHÔNG PITR được."
    luu_y "Chạy: ./scripts/sao-luu-goc.sh"
    hong=1
else
    ok "Có base backup để replay WAL lên."
fi

# ---- 6 · Kho WAL trên host --------------------------------------------------
if [ -d "$WAL_DIR" ]; then
    so=$(find "$WAL_DIR" -name '*.gz' -type f 2>/dev/null | wc -l | tr -d ' ')
    ok "Kho WAL: $so segment · $(du -sh "$WAL_DIR" 2>/dev/null | cut -f1)"
fi

echo
if [ "$hong" -eq 0 ]; then
    ok "WAL archiving khoẻ. R4 = mất tối đa 1 phút."
else
    loi "Có $hong nhóm vấn đề ở trên. Đọc từ trên xuống."
fi
exit "$hong"
