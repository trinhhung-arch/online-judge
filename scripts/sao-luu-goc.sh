#!/usr/bin/env bash
# =============================================================================
# Base backup VẬT LÝ — mảnh còn thiếu của WAL archiving (R4, nfrplan.md 5.3).
#
# ★ VÌ SAO FILE NÀY PHẢI TỒN TẠI, VÀ VÌ SAO sao-luu-db.sh KHÔNG THAY ĐƯỢC NÓ
#
# `sao-luu-db.sh` chạy `pg_dump` — một bản sao lưu LOGIC: nó là câu lệnh SQL dựng lại dữ
# liệu. WAL thì ghi lại thay đổi ở mức KHỐI BYTE của file dữ liệu. Hai thứ nói hai ngôn
# ngữ khác nhau, và **không có cách nào replay WAL lên một bản pg_dump đã restore**.
#
# Nghĩa là: bật archive_mode mà không có file này thì thư mục WAL đầy dần lên bằng những
# file không dùng được vào việc gì. PITR = base backup vật lý + WAL nối tiếp từ đúng thời
# điểm base backup ấy. Thiếu vế đầu là không có PITR, chỉ có một cảm giác an toàn.
#
# Hai bản sao lưu KHÔNG thừa nhau, chúng trả lời hai câu hỏi khác nhau:
#
#   pg_dump          "cho tôi lại dữ liệu"        — chạy được trên Postgres phiên bản khác,
#                                                    kiến trúc khác, phục hồi từng bảng được
#   pg_basebackup    "cho tôi lại đúng thời điểm" — cùng phiên bản, cùng kiến trúc, nhưng
#                                                    replay được WAL tới từng giây
#
# Giữ cả hai. Bản dump là đường thoát khi bản vật lý không dùng được (khác phiên bản, khác
# kiến trúc — host là Mac ARM, máy dev là WSL x86, xem ADR 006).
#
# Tần suất: MỖI NGÀY là đủ. WAL lấp khoảng giữa hai lần. Base backup càng cũ thì restore
# càng phải replay nhiều WAL, tức là RTO càng dài — đó là thứ quyết định tần suất, không
# phải RPO.
# =============================================================================
set -uo pipefail
# ★ umask 077: bản sao lưu chứa băm mật khẩu, email, mã nguồn mọi bài nộp — chỉ chủ máy đọc
#   được. Trước 2026-09-24 file ra 644 (rà soát bảo mật, F5).
umask 077

CONTAINER=${OJ_PG_CONTAINER:-oj-postgres}
NGUOI_DUNG=${OJ_DB_DUMP_USER:-ojuser}
# ★ psql mặc định lấy TÊN DATABASE = tên user. Thiếu -d thì nó tìm một database
# tên "ojuser" và chết với "database does not exist" — đo thật 2026-09-21.
DB=${OJ_DB_NAME:-ojdb}
THU_MUC=${OJ_BASEBACKUP_DIR:-$HOME/oj-backup/goc}
WAL_DIR=${OJ_WAL_ARCHIVE_DIR:-$HOME/oj-backup/wal}
GIU=${OJ_BASEBACKUP_KEEP:-3}

# Schema trần đã ~40KB ở bản dump; một base backup vật lý luôn lớn hơn nhiều lần vì nó chứa
# cả catalog, cả index, cả khoảng trống fillfactor.
TOI_THIEU_BYTE=${OJ_BASEBACKUP_MIN_BYTES:-1000000}

loi()   { echo "✗ $*" >&2; }
ok()    { echo "✓ $*"; }
luu_y() { echo "  ! $*"; }

moc="$THU_MUC/moc-thanh-cong"

# ─────────────────────────────────────────────────────────────────────────────
# --kiem: base backup gần nhất bao lâu rồi. Cắm vào bảng vận hành.
# ─────────────────────────────────────────────────────────────────────────────
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
    # Quá 48 giờ nghĩa là job đã chết một ngày mà không ai biết.
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

# ★ Từ chối chạy nếu archive_mode tắt. Một base backup vật lý mà không có WAL nối tiếp thì
#   chỉ khôi phục được về đúng giây nó được chụp — tức là nó KHÔNG hơn gì bản pg_dump, mà
#   lại nặng hơn nhiều lần. Chạy nó trong trạng thái ấy là tiêu đĩa để mua một ảo giác.
che_do=$(docker exec "$CONTAINER" psql -U "$NGUOI_DUNG" -d "$DB" -tAc "SHOW archive_mode" 2>/dev/null)
if [ "$che_do" != "on" ]; then
    loi "archive_mode = '${che_do:-?}', không phải 'on'."
    luu_y "Base backup không có WAL nối tiếp thì không PITR được. Xem docker-compose.yml."
    exit 1
fi

dau_ten=$(date '+%Y%m%d-%H%M%S')
dich="$THU_MUC/goc-$dau_ten.tar.gz"
tam="$dich.dang-ghi"

# -Ft -X fetch: một luồng tar duy nhất ra stdout, kèm luôn các segment WAL cần để bản thân
# nó nhất quán. KHÔNG dùng -X stream với -D -: stream cần một kết nối thứ hai ghi ra file
# riêng, không ghép được vào một luồng stdout.
#
# Ghi ra .dang-ghi rồi mới mv: cùng lý do với archive_command. Một file .tar.gz cụt nằm
# trong thư mục backup là thứ người ta sẽ tin tưởng vào đúng hôm không nên tin.
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

# gzip -t đọc hết file và kiểm CRC. Nó bắt được bản cụt do đĩa đầy giữa chừng — thứ mà
# phép so kích thước ở trên bỏ lọt khi đĩa đầy ở đoạn cuối.
if ! gzip -t "$tam" 2>/dev/null; then
    loi "File gzip hỏng CRC — đĩa đầy giữa chừng?"
    rm -f "$tam"
    exit 1
fi

mv "$tam" "$dich"
date +%s > "$moc"
ok "$(basename "$dich") — $(du -h "$dich" | cut -f1)"

# ─────────────────────────────────────────────────────────────────────────────
# Vòng xoay — và đây là chỗ WAL được dọn
#
# ★ WAL CŨ HƠN BASE BACKUP CŨ NHẤT LÀ RÁC, nhưng WAL mới hơn nó thì TUYỆT ĐỐI KHÔNG
#   được xoá: thiếu một segment ở giữa là chuỗi replay đứt, và mọi WAL sau đó cũng thành
#   vô dụng. Nên thứ tự ở đây là: xoá base backup thừa TRƯỚC, rồi mới lấy mốc của bản cũ
#   nhất còn lại làm ranh giới xoá WAL. Làm ngược lại là tự cắt chân mình.
# ─────────────────────────────────────────────────────────────────────────────
n=0
for f in $(ls -t "$THU_MUC"/goc-*.tar.gz 2>/dev/null); do
    n=$((n+1))
    [ "$n" -gt "$GIU" ] && rm -f "$f"
done

cu_nhat=$(ls -tr "$THU_MUC"/goc-*.tar.gz 2>/dev/null | head -1)
if [ -n "$cu_nhat" ] && [ -d "$WAL_DIR" ]; then
    truoc=$(find "$WAL_DIR" -name '*.gz' -type f ! -newer "$cu_nhat" 2>/dev/null | wc -l | tr -d ' ')
    find "$WAL_DIR" -name '*.gz' -type f ! -newer "$cu_nhat" -delete 2>/dev/null
    # File .tmp là rác của một lượt archive_command chết giữa chừng. Postgres sẽ gọi lại,
    # và `test ! -f %f.gz` không thấy chúng, nên chúng không chặn gì — chỉ tốn chỗ.
    find "$WAL_DIR" -name '*.gz.tmp' -type f -mmin +60 -delete 2>/dev/null
    [ "$truoc" -gt 0 ] && ok "Dọn $truoc segment WAL cũ hơn $(basename "$cu_nhat")."
fi

ok "Giữ $GIU bản gốc gần nhất. WAL từ bản cũ nhất trở đi được giữ nguyên."
