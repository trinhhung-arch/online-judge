#!/usr/bin/env bash
# =============================================================================
# Khôi phục Postgres từ bản sao lưu — và, quan trọng hơn, DIỄN TẬP khôi phục.
#
#   ./scripts/khoi-phuc-db.sh                  # diễn tập: restore vào DB tạm, bấm giờ, rồi xoá
#   ./scripts/khoi-phuc-db.sh <file.dump>      # diễn tập với một bản cụ thể
#   ./scripts/khoi-phuc-db.sh --that <file>    # KHÔI PHỤC THẬT, đè lên ojdb (hỏi xác nhận)
#
# ★ VÌ SAO CHẾ ĐỘ MẶC ĐỊNH LÀ DIỄN TẬP CHỨ KHÔNG PHẢI KHÔI PHỤC
# nfrplan.md 5.3: "một backup chưa từng được restore không phải là backup — nó là một thư
# mục file mà bạn hy vọng dùng được". Việc thường xuyên phải làm là DIỄN TẬP; khôi phục thật
# là việc làm một lần trong đời, trong hoảng loạn. Đặt việc nguy hiểm sau một cờ, và đặt việc
# nên làm hàng tuần ở chỗ gõ ít phím nhất.
#
# Diễn tập KHÔNG đụng vào ojdb: nó dựng một database riêng trong cùng container, đối chiếu số
# dòng với DB đang chạy, rồi xoá. Chạy được cả khi hệ thống đang phục vụ người dùng.
#
# ★ CON SỐ PHẢI ĐẠT: RTO ≤ 30 phút (R5). Script bấm giờ phần restore. Phần còn lại của 30
#   phút là thời gian con người: nhận ra sự cố, quyết định, dựng lại API và worker.
# =============================================================================
set -uo pipefail

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
DB=${OJ_DB_NAME:-ojdb}
# Mỗi database MỘT thư mục. Chung thư mục thì phép so kích thước với bản trước (ở dưới) đem
# bản của DB này so với bản của DB kia, và vòng xoay xoá bản của DB này để giữ bản của DB kia.
# Host có cả ojdb (dev) lẫn ojdb_prod trong cùng một container, nên đây không phải giả định.
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

# ─────────────────────────────────────────────────────────────────────────────
# KHÔI PHỤC THẬT — hỏi xác nhận bằng cách gõ tên database.
#
# Không dùng câu hỏi y/N: gõ 'y' là phản xạ, gõ đúng tên database thì phải đọc câu hỏi.
# Và không bao giờ chạy được khi không có người ngồi trước bàn phím (kiểm -t 0), nên một
# lần gọi nhầm từ cron hay từ script khác sẽ dừng chứ không xoá gì.
# ─────────────────────────────────────────────────────────────────────────────
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

    # Bản sao an toàn của trạng thái SẮP BỊ ĐÈ. Nếu bản dump hoá ra hỏng thì đây là đường lùi
    # duy nhất — và đúng lúc khôi phục là lúc người ta ít nghĩ tới đường lùi nhất.
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

# pg_restore trả khác 0 cả khi chỉ là cảnh báo (ví dụ role không tồn tại trong DB đích).
# Nên KHÔNG dựa vào mã thoát: giữ log, rồi kiểm bằng số dòng thật ở dưới — thứ đó không nói dối.
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

# ---- Đối chiếu số dòng ------------------------------------------------------
# Đây mới là phép kiểm thật. Một restore "thành công" vào một database rỗng cũng thoát 0.
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
        # Lệch là BÌNH THƯỜNG: bản sao lưu chụp lúc trước, hệ thống vẫn chạy tiếp sau đó.
        # Chỉ đánh dấu khi bản khôi phục RỖNG mà bản đang chạy thì không — đó là restore hỏng.
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
