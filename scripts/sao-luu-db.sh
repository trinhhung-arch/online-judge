#!/usr/bin/env bash
# =============================================================================
# Sao lưu Postgres — hiện thân của R4 (RPO ≤ 15 phút, nfrplan.md 5.3).
#
#   ./scripts/sao-luu-db.sh              # một lượt sao lưu
#   ./scripts/sao-luu-db.sh --kiem       # chỉ kiểm: lần cuối thành công là bao giờ
#
# Chạy tự động mỗi 15 phút bằng launchd — xem infra/launchd/dev.oj.sao-luu.plist.
#
# ★ VÌ SAO FILE NÀY PHẢI CÓ TRƯỚC KHI TRỎ TÊN MIỀN
# Bất biến #2 của CLAUDE.md là "không mất bài nộp", và nó là bất biến DUY NHẤT được mô tả
# bằng câu "lỗi không sửa được, không xin lỗi được". Trước file này, toàn bộ dữ liệu của hệ
# thống nằm trong đúng một docker volume trên đúng một ổ SSD. Không có bản thứ hai ở đâu cả.
#
# ★ BA CÁI BẪY, VÀ CẢ BA ĐỀU IM LẶNG
#
#  1. `pg_dump` của macOS đánh vào server 16 -> "server version mismatch", nhưng chỉ khi
#     phiên bản client lệch. Máy này cài Postgres qua Homebrew lúc nào thì client đổi lúc
#     ấy, và bản sao lưu chết vào một ngày không ai đụng vào script. Nên script KHÔNG dùng
#     pg_dump của máy: nó `docker exec` vào chính container, nơi client và server luôn khớp.
#
#  2. Đĩa đầy thì `pg_dump` vẫn chạy và vẫn thoát 0, chỉ có file là cụt. Ghi thẳng đè lên
#     chỗ cũ nghĩa là một bản sao lưu hỏng vừa thay thế một bản sao lưu tốt. Nên ở đây:
#     ghi ra file tạm -> KIỂM -> rồi mới đổi tên vào chỗ chính thức.
#
#  3. Một job nền dừng chạy nhìn giống hệt một job nền đang chạy tốt: cả hai đều im lặng.
#     Nên mỗi lượt thành công ghi mốc thời gian vào `moc-thanh-cong`, và `--kiem` đọc nó.
#     Không có ô này thì phát hiện ra backup chết vào đúng hôm cần tới nó.
#
# ★ KIỂM Ở ĐÂY KHÔNG PHẢI LÀ RESTORE. Xem scripts/khoi-phuc-db.sh — nfrplan 5.3 nói thẳng:
#   "một backup chưa từng được restore không phải là backup".
# =============================================================================
set -uo pipefail

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
DB=${OJ_DB_NAME:-ojdb}
# Mỗi database MỘT thư mục. Chung thư mục thì phép so kích thước với bản trước (ở dưới) đem
# bản của DB này so với bản của DB kia, và vòng xoay xoá bản của DB này để giữ bản của DB kia.
# Host có cả ojdb (dev) lẫn ojdb_prod trong cùng một container, nên đây không phải giả định.
THU_MUC=${OJ_BACKUP_DIR:-$HOME/oj-backup/$DB}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}

# nfrplan 5.3: "giữ 7 bản gần nhất theo giờ + 4 bản theo ngày".
GIU_GIO=${OJ_BACKUP_KEEP_HOURLY:-7}
GIU_NGAY=${OJ_BACKUP_KEEP_DAILY:-4}

# Dưới ngần này byte thì gần như chắc chắn là dump cụt: schema trần của hệ thống đã ~40KB.
TOI_THIEU_BYTE=${OJ_BACKUP_MIN_BYTES:-40000}
# Bản mới nhỏ hơn bản trước quá ngưỡng này là dấu hiệu mất dữ liệu, không phải "nén tốt hơn".
TY_LE_TOI_THIEU=${OJ_BACKUP_MIN_RATIO:-50}   # phần trăm

loi()   { echo "✗ $*" >&2; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

moc="$THU_MUC/moc-thanh-cong"

# ─────────────────────────────────────────────────────────────────────────────
# --kiem: đọc mốc, không sao lưu. Đây là thứ để cắm vào bảng vận hành hoặc gọi tay.
# ─────────────────────────────────────────────────────────────────────────────
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
        loi "Quá 20 phút — R4 đòi RPO ≤ 15 phút. Job launchd có còn chạy không?"
        echo "     launchctl list | grep dev.oj.sao-luu    (cột giữa khác 0 = job chết)" >&2
        echo "     tail ~/oj-backup/sao-luu.log             (lý do thật nằm ở đây)" >&2
        exit 1
    fi
    ok "Trong ngưỡng RPO 15 phút."
    exit 0
fi

# ─────────────────────────────────────────────────────────────────────────────
echo "── Sao lưu $DB ──"

mkdir -p "$THU_MUC/gio" "$THU_MUC/ngay" || { loi "Không tạo được $THU_MUC"; exit 1; }

if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null)" != true ]; then
    loi "Container $CONTAINER không chạy. Không sao lưu được."
    exit 1
fi

# Còn bao nhiêu chỗ trống. Cảnh báo ở 85% — nfrplan gọi "đầy disk" là thứ CHƯA AI LÀM GÌ CẢ,
# kể cả cảnh báo ở 80%. Một dòng luu_y ở đây rẻ hơn nhiều so với phát hiện lúc mất dữ liệu.
dung=$(df -P "$THU_MUC" | awk 'NR==2 {gsub(/%/,"",$5); print $5}')
if [ -n "$dung" ] && [ "$dung" -ge 85 ]; then
    luu_y "Ổ chứa backup đã dùng ${dung}%. Dọn chỗ, hoặc đổi OJ_BACKUP_DIR sang ổ ngoài."
fi

dau_ten=$(date '+%Y%m%d-%H%M')
tam="$THU_MUC/gio/.dang-ghi-$dau_ten.dump"
dich="$THU_MUC/gio/oj-$dau_ten.dump"

# -Fc (custom): nén sẵn, và quan trọng hơn là CÓ MỤC LỤC — nhờ nó `pg_restore --list` kiểm
# được tính toàn vẹn mà không phải restore thật. Dump dạng SQL thuần không kiểm được như thế.
# --no-owner: bản dump phải restore được vào một DB trắng nơi role oj_app/oj_migrator chưa có.
if ! docker exec "$CONTAINER" pg_dump -U "$NGUOI_DUNG" -d "$DB" -Fc --no-owner > "$tam" 2>"$tam.loi"; then
    loi "pg_dump hỏng:"
    sed 's/^/     /' "$tam.loi" >&2
    rm -f "$tam" "$tam.loi"
    exit 1
fi
rm -f "$tam.loi"

# ---- Kiểm TRƯỚC khi đưa vào chỗ chính thức ----------------------------------
byte=$(wc -c < "$tam" | tr -d ' ')
if [ "$byte" -lt "$TOI_THIEU_BYTE" ]; then
    loi "Dump chỉ $byte byte, dưới ngưỡng $TOI_THIEU_BYTE. Nhiều khả năng cụt hoặc DB rỗng."
    rm -f "$tam"
    exit 1
fi

# Mục lục đọc được = header và TOC nguyên vẹn. Không chứng minh từng dòng dữ liệu đúng,
# nhưng bắt được đúng loại hỏng hay gặp nhất: file cụt vì hết đĩa hoặc container chết giữa chừng.
if ! docker exec -i "$CONTAINER" pg_restore --list < "$tam" > /dev/null 2>&1; then
    loi "pg_restore --list không đọc được file — dump hỏng. GIỮ NGUYÊN bản cũ."
    rm -f "$tam"
    exit 1
fi

# Có mục lục nhưng thiếu bảng nộp bài thì cũng vô dụng. Năm bảng này là tối thiểu.
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

# So với bản trước. Dữ liệu chỉ có tăng, nên co lại đột ngột là tín hiệu, không phải may mắn.
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

# ---- Bản theo ngày ----------------------------------------------------------
# Bản đầu tiên của mỗi ngày được giữ lại lâu hơn. Dùng liên kết cứng: không tốn thêm đĩa,
# và xoá bên vòng-giờ không làm mất bên vòng-ngày.
hom_nay="$THU_MUC/ngay/oj-$(date '+%Y%m%d').dump"
if [ ! -e "$hom_nay" ]; then
    ln "$dich" "$hom_nay" 2>/dev/null || cp "$dich" "$hom_nay"
    ok "giữ thêm bản ngày $(basename "$hom_nay")"
fi

# ---- Vòng xoay --------------------------------------------------------------
# Xoá sau khi bản mới đã nằm yên chỗ, không bao giờ trước.
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

# nfrplan 5.3 đòi HAI nơi lưu: ổ ngoài gắn Mac VÀ cloud. Chỉ một nơi thì cháy nhà là mất cả
# hệ thống lẫn bản sao của nó. Không tự cấu hình rclone ở đây vì nó cần tài khoản của người.
if [ -z "${OJ_BACKUP_RCLONE_DICH:-}" ]; then
    luu_y "Chưa có bản sao ngoài máy này. nfrplan 5.3 đòi ổ ngoài + cloud."
    luu_y "Đặt OJ_BACKUP_RCLONE_DICH=<remote:thư-mục> sau khi 'rclone config'."
elif command -v rclone >/dev/null; then
    if rclone copy "$dich" "$OJ_BACKUP_RCLONE_DICH" 2>/dev/null; then
        ok "đã đẩy lên $OJ_BACKUP_RCLONE_DICH"
    else
        # KHÔNG exit 1: bản local đã tốt và đã ghi mốc. Mạng hỏng không phải lý do để
        # báo cả lượt sao lưu là thất bại.
        luu_y "Đẩy lên $OJ_BACKUP_RCLONE_DICH HỎNG — bản local vẫn tốt."
    fi
else
    luu_y "Có OJ_BACKUP_RCLONE_DICH nhưng chưa cài rclone: brew install rclone"
fi
