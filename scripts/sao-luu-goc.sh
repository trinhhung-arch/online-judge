#!/usr/bin/env bash
set -uo pipefail
umask 077

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}
DB=${OJ_DB_NAME:-ojdb}
THU_MUC=${OJ_BASEBACKUP_DIR:-$HOME/oj-backup/goc}
WAL_DIR=${OJ_WAL_ARCHIVE_DIR:-$HOME/oj-backup/wal}
GIU=${OJ_BASEBACKUP_KEEP:-3}

TOI_THIEU_BYTE=${OJ_BASEBACKUP_MIN_BYTES:-1000000}

loi()   { echo "✗ $*" >&2; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

moc="$THU_MUC/moc-thanh-cong"

if [ "${1:-}" = "--kiem" ]; then
    if [ ! -f "$moc" ]; then
        loi "Chưa từng có base backup nào ($moc không tồn tại)."
        luu_y "WAL đang được lưu nhưng KHÔNG PITR được: thiếu vế base backup."
        exit 1
    fi
    truoc=$(cat "$moc")
    gio_phut=$(( ($(date +%s) - truoc) / 3600 ))
    echo "Base backup gần nhất: $(date -r "$truoc" '+%Y-%m-%d %H:%M:%S') (${gio_phut} giờ trước)"
    ls -lh "$THU_MUC" 2>/dev/null | grep -E '\.tar\.gz$' | awk '{print "   " $9 "  " $5}'
    if [ "$gio_phut" -gt 48 ]; then
        loi "Quá 48 giờ. Job có còn chạy không?"
        exit 1
    fi
    ok "Trong ngưỡng."
    exit 0
fi

mkdir -p "$THU_MUC" || { loi "Không tạo được $THU_MUC"; exit 1; }

if ! docker ps --format '{{.Names}}' | grep -qx "$CONTAINER"; then
    loi "Container $CONTAINER không chạy."
    exit 1
fi

che_do=$(docker exec "$CONTAINER" psql -U "$NGUOI_DUNG" -d "$DB" -tAc "SHOW archive_mode" 2>/dev/null)
if [ "$che_do" != "on" ]; then
    loi "archive_mode = '${che_do:-?}', không phải 'on'."
    luu_y "Base backup không có WAL nối tiếp thì không PITR được. Xem docker-compose.yml."
    exit 1
fi

dau_ten=$(date '+%Y%m%d-%H%M%S')
dich="$THU_MUC/goc-$dau_ten.tar.gz"
tam="$dich.dang-ghi"

if ! docker exec "$CONTAINER" pg_basebackup -U "$NGUOI_DUNG" -D - -Ft -X fetch \
        --checkpoint=fast 2>"$tam.loi" | gzip > "$tam"; then
    loi "pg_basebackup hỏng:"
    sed 's/^/    /' "$tam.loi" >&2
    rm -f "$tam" "$tam.loi"
    exit 1
fi
rm -f "$tam.loi"

co=$(wc -c < "$tam" | tr -d ' ')
if [ "$co" -lt "$TOI_THIEU_BYTE" ]; then
    loi "Chỉ $co byte — dưới ngưỡng $TOI_THIEU_BYTE. Gần như chắc chắn là bản cụt."
    rm -f "$tam"
    exit 1
fi

if ! gzip -t "$tam" 2>/dev/null; then
    loi "File gzip hỏng CRC — đĩa đầy giữa chừng?"
    rm -f "$tam"
    exit 1
fi

mv "$tam" "$dich"
date +%s > "$moc"
ok "$(basename "$dich") — $(du -h "$dich" | cut -f1)"

n=0
for f in $(ls -t "$THU_MUC"/goc-*.tar.gz 2>/dev/null); do
    n=$((n+1))
    [ "$n" -gt "$GIU" ] && rm -f "$f"
done

cu_nhat=$(ls -tr "$THU_MUC"/goc-*.tar.gz 2>/dev/null | head -1)
if [ -n "$cu_nhat" ] && [ -d "$WAL_DIR" ]; then
    truoc=$(find "$WAL_DIR" -name '*.gz' -type f ! -newer "$cu_nhat" 2>/dev/null | wc -l | tr -d ' ')
    find "$WAL_DIR" -name '*.gz' -type f ! -newer "$cu_nhat" -delete 2>/dev/null
    find "$WAL_DIR" -name '*.gz.tmp' -type f -mmin +60 -delete 2>/dev/null
    [ "$truoc" -gt 0 ] && ok "Dọn $truoc segment WAL cũ hơn $(basename "$cu_nhat")."
fi

ok "Giữ $GIU bản gốc gần nhất. WAL từ bản cũ nhất trở đi được giữ nguyên."
