#!/usr/bin/env bash

set -uo pipefail

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
DB=${OJ_DB_NAME:-ojdb}
THU_MUC=${OJ_BACKUP_DIR:-$HOME/oj-backup/$DB}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}
DB_TAM=${OJ_DB_DRILL_NAME:-ojdb_dientap}
RTO_GIAY=${OJ_RTO_SECONDS:-1800}

loi()   { echo "✗ $*" >&2; exit 1; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

that=0
file=""
for a in "$@"; do
    case "$a" in
        --that) that=1 ;;
        -*) loi "Tham số lạ: $a" ;;
        *)  file=$a ;;
    esac
done

[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" = true ] \
    || loi "Container $CONTAINER không chạy."

if [ -z "$file" ]; then
    file=$(ls -t "$THU_MUC/gio"/oj-*.dump 2>/dev/null | head -1)
    [ -n "$file" ] || loi "Không có bản sao lưu nào trong $THU_MUC/gio. Chạy ./scripts/sao-luu-db.sh trước."
fi
[ -f "$file" ] || loi "Không thấy file $file"

echo "Bản sao lưu: $file"
echo "             $(du -h "$file" | cut -f1) · tạo lúc $(date -r "$file" '+%Y-%m-%d %H:%M:%S')"
echo

if [ "$that" -eq 1 ]; then
    echo "⚠️  KHÔI PHỤC THẬT: toàn bộ dữ liệu hiện có trong '$DB' sẽ bị XOÁ và thay bằng"
    echo "    nội dung của bản sao lưu trên. Mọi bài nộp sau mốc $(date -r "$file" '+%H:%M') sẽ MẤT."
    echo
    echo "    DỪNG API VÀ WORKER TRƯỚC KHI TIẾP TỤC — chúng đang ghi vào database này."
    echo
    [ -t 0 ] || loi "Không có terminal. Khôi phục thật chỉ chạy khi có người xác nhận."
    printf "    Gõ đúng tên database để xác nhận (%s): " "$DB"
    read -r tra_loi
    [ "$tra_loi" = "$DB" ] || loi "Không khớp. Không làm gì cả."

    truoc="$THU_MUC/truoc-khi-khoi-phuc-$(date '+%Y%m%d-%H%M%S').dump"
    echo
    echo "Chụp lại trạng thái hiện tại trước đã..."
    if docker exec "$CONTAINER" pg_dump -U "$NGUOI_DUNG" -d "$DB" -Fc --no-owner > "$truoc" 2>/dev/null; then
        ok "đã lưu $truoc"
    else
        luu_y "Không chụp được trạng thái hiện tại (DB có thể đã hỏng). Đi tiếp."
        rm -f "$truoc"
    fi

    dich=$DB
    bat_dau=$(date +%s)
    docker exec "$CONTAINER" dropdb -U "$NGUOI_DUNG" --if-exists --force "$DB"  || loi "dropdb hỏng"
    docker exec "$CONTAINER" createdb -U "$NGUOI_DUNG" "$DB"                    || loi "createdb hỏng"
else
    dich=$DB_TAM
    echo "── Diễn tập vào '$DB_TAM' (KHÔNG đụng '$DB') ──"
    bat_dau=$(date +%s)
    docker exec "$CONTAINER" dropdb -U "$NGUOI_DUNG" --if-exists --force "$DB_TAM" >/dev/null 2>&1
    docker exec "$CONTAINER" createdb -U "$NGUOI_DUNG" "$DB_TAM" || loi "Không tạo được $DB_TAM"
fi

log=$(mktemp)
docker exec -i "$CONTAINER" pg_restore -U "$NGUOI_DUNG" -d "$dich" --no-owner < "$file" 2>"$log"
ma=$?
giay=$(( $(date +%s) - bat_dau ))

echo
if [ "$ma" -ne 0 ]; then
    so_loi=$(grep -c 'error' "$log" 2>/dev/null || echo 0)
    luu_y "pg_restore thoát $ma với $so_loi dòng lỗi — đọc kỹ, đừng bỏ qua:"
    grep -i 'error' "$log" | head -5 | sed 's/^/     /'
fi
rm -f "$log"

dem() {
    docker exec "$CONTAINER" psql -U "$NGUOI_DUNG" -d "$1" -tAc \
        "SELECT count(*) FROM $2" 2>/dev/null | tr -d ' \r'
}

echo "── Số dòng ──"
lech=0
printf "  %-16s %10s %10s\n" "bảng" "khôi phục" "đang chạy"
for bang in users problems testcases submissions judge_runs judge_hosts; do
    a=$(dem "$dich" "$bang"); a=${a:-?}
    if [ "$that" -eq 1 ]; then
        printf "  %-16s %10s %10s\n" "$bang" "$a" "-"
    else
        b=$(dem "$DB" "$bang"); b=${b:-?}
        dau=" "
        [ "$a" = "0" ] && [ "$b" != "0" ] && { dau="✗"; lech=$((lech + 1)); }
        printf "%s %-16s %10s %10s\n" "$dau" "$bang" "$a" "$b"
    fi
done

echo
echo "── Thời gian restore: ${giay}s (RTO cho phép ${RTO_GIAY}s) ──"
if [ "$giay" -gt "$RTO_GIAY" ]; then
    luu_y "Vượt R5. Phần restore một mình đã hết ngân sách, chưa tính thời gian con người."
fi

if [ "$that" -eq 1 ]; then
    echo
    ok "Đã khôi phục '$DB'."
    echo
    echo "  Ba việc còn lại, đúng thứ tự:"
    echo "   1. ./mvnw -pl oj-api flyway:info    — schema phải khớp mã nguồn, không Pending"
    echo "   2. khởi động lại oj-api và oj-worker"
    echo "   3. ./scripts/sao-luu-db.sh          — bản sao đầu tiên của đời sống mới"
    exit 0
fi

docker exec "$CONTAINER" dropdb -U "$NGUOI_DUNG" --if-exists --force "$DB_TAM" >/dev/null 2>&1
if [ "$lech" -gt 0 ]; then
    loi "$lech bảng rỗng sau khi khôi phục. BẢN SAO LƯU NÀY KHÔNG DÙNG ĐƯỢC."
fi
ok "Diễn tập đạt. Bản sao lưu này khôi phục được, DB tạm đã dọn."
