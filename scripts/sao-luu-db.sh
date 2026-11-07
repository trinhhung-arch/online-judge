#!/usr/bin/env bash
set -uo pipefail
umask 077

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
DB=${OJ_DB_NAME:-ojdb}
THU_MUC=${OJ_BACKUP_DIR:-$HOME/oj-backup/$DB}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}

GIU_GIO=${OJ_BACKUP_KEEP_HOURLY:-7}
GIU_NGAY=${OJ_BACKUP_KEEP_DAILY:-4}

TOI_THIEU_BYTE=${OJ_BACKUP_MIN_BYTES:-40000}
TY_LE_TOI_THIEU=${OJ_BACKUP_MIN_RATIO:-50}

loi()   { echo "✗ $*" >&2; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

moc="$THU_MUC/moc-thanh-cong"

if [ "${1:-}" = "--kiem" ]; then
    if [ ! -f "$moc" ]; then
        loi "Chưa từng sao lưu thành công lần nào ($moc không tồn tại)."
        exit 1
    fi
    truoc=$(cat "$moc")
    gio=$(date +%s)
    phut=$(( (gio - truoc) / 60 ))
    echo "Lần sao lưu thành công gần nhất: $(date -r "$truoc" '+%Y-%m-%d %H:%M:%S') (${phut} phút trước)"
    ls -lh "$THU_MUC/gio" 2>/dev/null | tail -n +2 | awk '{print "   " $9 "  " $5}'
    if [ "$phut" -gt 20 ]; then
        loi "Quá 20 phút — nhịp dump là 15 phút. Job launchd có còn chạy không?"
        echo "     launchctl list | grep dev.oj.sao-luu    (cột giữa khác 0 = job chết)" >&2
        echo "     tail ~/oj-backup/sao-luu.log             (lý do thật nằm ở đây)" >&2
        exit 1
    fi
    ok "Trong nhịp 15 phút. (RPO thật do WAL quyết định — ./scripts/kiem-wal.sh)"
    exit 0
fi

echo "── Sao lưu $DB ──"

mkdir -p "$THU_MUC/gio" "$THU_MUC/ngay" || { loi "Không tạo được $THU_MUC"; exit 1; }

if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != true ]; then
    loi "Container $CONTAINER không chạy. Không sao lưu được."
    exit 1
fi

dung=$(df -P "$THU_MUC" | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
if [ -n "$dung" ] && [ "$dung" -ge 85 ]; then
    luu_y "Ổ chứa backup đã dùng ${dung}%. Dọn chỗ, hoặc đổi OJ_BACKUP_DIR sang ổ ngoài."
fi

dau_ten=$(date '+%Y%m%d-%H%M')
tam="$THU_MUC/gio/.dang-ghi-$dau_ten.dump"
dich="$THU_MUC/gio/oj-$dau_ten.dump"

if ! docker exec "$CONTAINER" pg_dump -U "$NGUOI_DUNG" -d "$DB" -Fc --no-owner > "$tam" 2>"$tam.loi"; then
    loi "pg_dump hỏng:"
    sed 's/^/     /' "$tam.loi" >&2
    rm -f "$tam" "$tam.loi"
    exit 1
fi
rm -f "$tam.loi"

byte=$(wc -c < "$tam" | tr -d ' ')
if [ "$byte" -lt "$TOI_THIEU_BYTE" ]; then
    loi "Dump chỉ $byte byte, dưới ngưỡng $TOI_THIEU_BYTE. Nhiều khả năng cụt hoặc DB rỗng."
    rm -f "$tam"
    exit 1
fi

if ! docker exec -i "$CONTAINER" pg_restore --list < "$tam" > /dev/null 2>&1; then
    loi "pg_restore --list không đọc được file — dump hỏng. GIỮ NGUYÊN bản cũ."
    rm -f "$tam"
    exit 1
fi

muc_luc=$(docker exec -i "$CONTAINER" pg_restore --list < "$tam" 2>/dev/null)
thieu=""
for bang in users problems submissions judge_queue testcases; do
    echo "$muc_luc" | grep -q "TABLE DATA public $bang " || thieu="$thieu $bang"
done
if [ -n "$thieu" ]; then
    loi "Dump thiếu dữ liệu bảng:$thieu. GIỮ NGUYÊN bản cũ."
    rm -f "$tam"
    exit 1
fi

truoc_do=$(ls -t "$THU_MUC/gio"/oj-*.dump 2>/dev/null | head -1)
if [ -n "$truoc_do" ]; then
    byte_truoc=$(wc -c < "$truoc_do" | tr -d ' ')
    if [ "$byte_truoc" -gt 0 ] && [ $(( byte * 100 / byte_truoc )) -lt "$TY_LE_TOI_THIEU" ]; then
        loi "Dump mới ($byte B) nhỏ hơn $TY_LE_TOI_THIEU% bản trước ($byte_truoc B)."
        echo "     Đây có thể là mất dữ liệu thật. File để lại ở $tam để xem tay," >&2
        echo "     KHÔNG đưa vào vòng xoay và KHÔNG xoá bản cũ." >&2
        exit 1
    fi
fi

mv "$tam" "$dich"
ok "$(basename "$dich") — $(du -h "$dich" | cut -f1)"

hom_nay="$THU_MUC/ngay/oj-$(date '+%Y%m%d').dump"
if [ ! -e "$hom_nay" ]; then
    ln "$dich" "$hom_nay" 2>/dev/null || cp "$dich" "$hom_nay"
    ok "giữ thêm bản ngày $(basename "$hom_nay")"
fi

xoa_thua() {
    local thu_muc=$1 giu=$2 f n=0
    for f in $(ls -t "$thu_muc"/oj-*.dump 2>/dev/null); do
        n=$((n + 1))
        [ "$n" -gt "$giu" ] && rm -f "$f"
    done
}
xoa_thua "$THU_MUC/gio" "$GIU_GIO"
xoa_thua "$THU_MUC/ngay" "$GIU_NGAY"

date +%s > "$moc"

echo
ok "Xong. $(ls "$THU_MUC/gio"/oj-*.dump 2>/dev/null | wc -l | tr -d ' ') bản theo giờ, $(ls "$THU_MUC/ngay"/oj-*.dump 2>/dev/null | wc -l | tr -d ' ') bản theo ngày, tại $THU_MUC"

if [ -z "${OJ_BACKUP_RCLONE_DICH:-}" ]; then
    luu_y "Chưa có bản sao ngoài máy này. nfrplan 5.3 đòi ổ ngoài + cloud."
    luu_y "Đặt OJ_BACKUP_RCLONE_DICH=<remote:thư-mục> sau khi 'rclone config'."
elif command -v rclone >/dev/null; then
    if rclone copy "$dich" "$OJ_BACKUP_RCLONE_DICH" 2>/dev/null; then
        ok "đã đẩy lên $OJ_BACKUP_RCLONE_DICH"
    else
        luu_y "Đẩy lên $OJ_BACKUP_RCLONE_DICH HỎNG — bản local vẫn tốt."
    fi
else
    luu_y "Có OJ_BACKUP_RCLONE_DICH nhưng chưa cài rclone: brew install rclone"
fi
