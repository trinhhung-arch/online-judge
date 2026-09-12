#!/usr/bin/env bash
# =============================================================================
# Bộ đo tải — bốn kịch bản, mỗi kịch bản trả lời ĐÚNG MỘT câu hỏi của nfrplan:
#
#   BASE=http://<máy>:<cổng> ./chay.sh quet       P1/P2 theo số người ảo · R1/R2/R3 dưới quá tải
#   BASE=...                 ./chay.sh on-dinh    P3, P6 — 2 bài/s, 30 phút, mô hình mở   (2.4 b)
#   BASE=...                 ./chay.sh dot-bien   P5 — 500 bài cùng lúc, rút cạn, 0 mất   (2.4 a)
#   BASE=... CONTEST_ID=7    ./chay.sh sse        1000 kết nối SSE đồng thời              (2.4 c)
#   BASE=...                 ./chay.sh ky-thi     N người ĐĂNG NHẬP xem bảng xếp hạng + nộp bài · P8
#                            qua Cloudflare: BASE=https://<tên miền đo> — dựng bằng tunnel-do.sh
#
# ★ SỐ ĐỂ NGHIỆM THU CHỈ ĐẾN TỪ ĐỦ BA ĐIỀU KIỆN: stack KHÔNG phải prod · worker chạy isolate thật
#   (không phải ScriptedJudgeRunner) · k6 trên máy khác từ ~400 người ảo trở lên. Thiếu một điều thì
#   con số chỉ để dò đường — bảng kết luận nói rõ điều ấy.
#
# ★ "KHÔNG PHẢI PROD" NGHĨA LÀ API SAU BASE CHẠY TRÊN DATABASE ĐO RIÊNG — không chỉ "BASE là IP nội bộ".
#   Trên máy đang chạy prod, http://<IP-LAN>:8080 VẪN là prod. Vì thế trước khi ghi bất cứ gì, chay.sh
#   BẮT TAY: problemId của DE_MA ở API phải bằng id trong database của OJ_TAI_PSQL (chỉ đọc), và tai-1
#   vừa seed phải đăng nhập được qua BASE.
#
# ★ ĐƯỜNG CHẤM ĐẾM Ở DB, KHÔNG Ở k6. Mỗi lượt: chụp max(id) → chạy k6 → chờ dangCho VÀ dangCham về 0
#   → bao-cao-db.py đếm mọi bài có id lớn hơn mốc. Bản cũ chỉ chờ dangCho, nên bài đang chấm dở
#   bị tính nhầm là kẹt.
#
# Biến: BASE (bắt buộc) · OJ_TAI_PSQL (psql tới DB của stack; mặc định docker exec oj-postgres,
#       database ojdb) · OJ_TAI_DB (tên DB, để xác nhận seed) · RA · CAC_MUC · THOI_LUONG · NGHI
#       SO_TAI_KHOAN · TOC_DO · SO_BAI · CONTEST_ID · SO_KET_NOI · GIU · CHO_RUT_CAN
# =============================================================================
set -uo pipefail          # KHÔNG -e: k6 thoát 99 khi trượt ngưỡng, và trượt ngưỡng là DỮ LIỆU
# ★ KHÔNG `| grep -q` trong pipeline ở file này: với `set -o pipefail`, grep -q thoát ngay khi khớp,
#   lệnh bên trái ăn SIGPIPE (mã 141) và cả pipeline bị coi là HỎNG dù đã khớp. Đo 2026-09-11:
#   dung-stack-do.sh báo "RabbitMQ không lên sau 60s" với một RabbitMQ sẵn sàng sau 6s.
#   Dùng `grep ... >/dev/null` — grep đọc hết đầu vào, không đóng pipe sớm.

KICH_BAN=${1:-}
HERE=$(cd "$(dirname "$0")" && pwd)
case "$KICH_BAN" in quet|on-dinh|dot-bien|sse|ky-thi) ;; *)
    echo "Dùng: BASE=http://<máy>:<cổng> $0 quet|on-dinh|dot-bien|sse|ky-thi" >&2; exit 2 ;; esac

loi() { echo "✗ $*" >&2; exit 1; }

# ★ Worker PROD bị pause để đo (dung-stack-do.sh, mục CPU) thì site thật NGỪNG CHẤM cho tới khi
#   unpause. Đo 2026-09-11: quên bước đó sau lượt dot-bien, prod đứng chấm ~2 giờ mà không gì báo.
#   Nhắc ở EXIT để lượt đạt, lượt trượt, lượt lỗi và Ctrl+C đều in. INT/TERM đổi thành exit để
#   EXIT trap chắc chắn chạy.
nhac_worker_prod() {
    [ "$(docker inspect -f '{{.State.Paused}}' oj-worker 2>/dev/null)" = true ] || return 0
    echo
    echo "⚠ Worker PROD (oj-worker) vẫn đang PAUSE — site thật KHÔNG chấm bài. Đo xong thì:"
    echo "    docker unpause oj-worker"
}
# ky-thi chạy sse-tai.py NỀN và giữ phiên đăng nhập trong một file tạm: thoát kiểu gì cũng phải dọn cả hai.
PID_SSE='' PHIEN=''
khi_thoat() {
    [ -n "$PID_SSE" ] && kill "$PID_SSE" 2>/dev/null
    [ -n "$PHIEN" ] && rm -f "$PHIEN"
    nhac_worker_prod
}
trap khi_thoat EXIT
trap 'exit 130' INT TERM
: "${BASE:?Thiếu BASE. Không có mặc định, cố ý: localhost:8080 của máy chủ là API prod.}"
BASE=${BASE%/}

# Cùng luật với chung.js và sse-tai.py: chỉ máy nội bộ, trừ khi gõ lại đúng BASE.
host=$(printf '%s' "$BASE" | sed -E 's#^https?://(\[[^]]*\]|[^/:]+).*#\1#')
case "$host" in
    localhost|127.*|10.*|192.168.*|*.local|\[::1\]) ;;
    172.1[6-9].*|172.2[0-9].*|172.3[01].*) ;;
    *) [ "${CHO_PHEP_DICH_CONG_KHAI:-}" = "$BASE" ] \
        || loi "BASE $BASE không phải máy nội bộ. Cố ý thì đặt CHO_PHEP_DICH_CONG_KHAI=$BASE" ;;
esac

read -r -a PSQL <<< "${OJ_TAI_PSQL:-docker exec -i oj-postgres psql -U ojuser -d ojdb}"
export OJ_TAI_PSQL="${PSQL[*]}"
OJ_TAI_DB=${OJ_TAI_DB:-ojdb}
SO_TAI_KHOAN=${SO_TAI_KHOAN:-1000}
RA=${RA:-/tmp/oj-tai-trong}/$KICH_BAN-$(date +%Y%m%d-%H%M%S)

command -v python3 >/dev/null || loi "Cần python3."
[ "$KICH_BAN" = sse ] || command -v k6 >/dev/null || loi "Chưa có k6: brew install k6"
# 2>/dev/null ở CẢ hai vế: API chưa chạy thì curl trả rỗng, và json.load in 12 dòng traceback che mất
# câu lỗi thật (2026-09-11: chạy vào stack đo đã gỡ, người đọc chỉ thấy traceback). Nơi gọi tự xét mã thoát.
trang_thai() {
    curl -fsS --max-time 5 "$BASE/api/v1/status" 2>/dev/null | python3 -c \
        'import sys,json; d=json.load(sys.stdin); print(d["dangCho"], d["dangCham"], d["mayChamSong"])' 2>/dev/null
}
if ! trang_thai >/dev/null; then
    ma_tt=$(curl -s -o /dev/null -w '%{http_code}' --max-time 8 "$BASE/api/v1/status" 2>/dev/null)
    case "$host" in
        localhost|127.*|10.*|192.168.*|*.local|\[::1\]|172.1[6-9].*|172.2[0-9].*|172.3[01].*)
            loi "Không gọi được $BASE/api/v1/status (HTTP $ma_tt) — API sau BASE chưa chạy.
  Stack đo trên máy này đã gỡ thì dựng lại trước: $HERE/dung-stack-do.sh" ;;
        *)
            # Đo 2026-09-11: chạy ky-thi ngay sau `tunnel-do.sh --xoa` — câu cũ bảo "dựng lại stack đo"
            # trong khi stack vẫn sống, chỉ có tunnel đã tắt.
            loi "Không gọi được $BASE/api/v1/status (HTTP $ma_tt).
  530 = tunnel đo đang TẮT → mở lại: $HERE/tunnel-do.sh $host
  403 = luật WAF chặn chính máy này · 000 = DNS hoặc mạng · mã khác = xem stack đo: $HERE/dung-stack-do.sh" ;;
    esac
fi

# ---- Tiền kiểm: stack ĐÚNG, không phải prod, có máy chấm ---------------------
if [ "$KICH_BAN" != sse ]; then
    # ★ </dev/null ở MỌI lệnh psql không cần đầu vào: `docker exec -i` giữ stdin mở và NUỐT nó. Đo
    # 2026-09-11: `printf 'dong y' | ./chay.sh quet` in "Đã huỷ" — lệnh psql này ăn mất câu xác nhận.
    ten_db=$("${PSQL[@]}" -X -tAc 'SELECT current_database()' </dev/null 2>/dev/null) || loi "OJ_TAI_PSQL không kết nối được: ${PSQL[*]}"
    case "$ten_db" in *prod*|*PROD*) loi "OJ_TAI_PSQL trỏ vào '$ten_db' — database production." ;; esac
    [ "$ten_db" = "$OJ_TAI_DB" ] || loi "OJ_TAI_PSQL trỏ vào '$ten_db' nhưng OJ_TAI_DB='$OJ_TAI_DB'. Hai biến phải cùng một database."
    # ★ Bắt tay CHỈ ĐỌC. Đo 2026-09-11: BASE=http://192.168.1.2:8080 là IP LAN của chính máy chủ — qua
    # hàng rào "máy nội bộ" — và cổng 8080 là API prod (ojdb_prod), còn OJ_TAI_PSQL mặc định trỏ ojdb.
    # Hai đầu chưa từng được đối chiếu; lượt ấy chỉ dừng vì prod tình cờ không có đề A-PLUS-B.
    DE_MA=${DE_MA:-A-PLUS-B}
    id_api=$(curl -fsS --max-time 5 "$BASE/api/v1/problems/$(python3 -c 'import sys, urllib.parse; print(urllib.parse.quote(sys.argv[1]))' "$DE_MA")" 2>/dev/null \
        | python3 -c 'import sys, json; print(json.load(sys.stdin)["problemId"])' 2>/dev/null)
    id_db=$("${PSQL[@]}" -X -tA -v de_ma="$DE_MA" <<< "SELECT id FROM problems WHERE code = :'de_ma'" 2>/dev/null)
    [ -n "$id_db" ] || loi "Database '$ten_db' không có đề $DE_MA. Stack đo cần dev-seed, hoặc đặt DE_MA=<mã đề có testdata>."
    [ "$id_api" = "$id_db" ] || loi "API sau $BASE KHÔNG chạy trên database '$ten_db': đề $DE_MA ở API là '${id_api:-không có}', ở database là '$id_db'.
     Trên máy đang chạy prod, cổng 8080 là API prod. Load test cần một API trỏ vào database đo riêng
     (cổng khác, hoặc cửa sổ bảo trì) — xem đầu file. KHÔNG có gì được ghi."
    read -r _ _ song <<< "$(trang_thai)"
    [ "${song:-0}" -ge 1 ] || [ "${CHO_PHEP_KHONG_MAY_CHAM:-}" = 1 ] \
        || loi "mayChamSong = ${song:-0}: không có worker nào — mọi số đường chấm sẽ đo một hệ thống không chấm bài."
    "${PSQL[@]}" -X -q -v xac_nhan_db="$OJ_TAI_DB" -v so_nguoi="$SO_TAI_KHOAN" < "$HERE/seed-nguoi-dung.sql" >/dev/null \
        || loi "seed-nguoi-dung.sql hỏng (hàng rào từ chối, hoặc schema lệch)."
    than=$(python3 -c 'import json, sys; print(json.dumps({"dinhDanh": "tai-1", "password": sys.argv[1]}))' "${MAT_KHAU:-matkhau-dev-123}")
    curl -fsS --max-time 10 -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' -d "$than" 2>/dev/null \
        | grep accessToken >/dev/null || loi "Đã seed vào '$ten_db' nhưng API sau $BASE không đăng nhập được tai-1 — API không dùng database này."
fi

echo
echo "⚠ Kịch bản '$KICH_BAN' GHI DỮ LIỆU THẬT vào stack sau $BASE${ten_db:+ (database '$ten_db')}."
read -r -p "  Gõ 'dong y' để chạy: " tra_loi
[ "$tra_loi" = "dong y" ] || { echo "Đã huỷ."; exit 1; }
mkdir -p "$RA"

db_now()    { "${PSQL[@]}" -X -tAc 'SELECT now()' </dev/null; }
db_id_max() { "${PSQL[@]}" -X -tAc 'SELECT coalesce(max(id), 0) FROM submissions' </dev/null; }

# Rỗng = không còn bài CHỜ và không còn bài ĐANG CHẤM. Ghi RUT_CAN_XONG để bao-cao-db.py biết
# "còn QUEUED" là kẹt (đã rút cạn xong) hay chỉ là chậm (hết giờ chờ).
cho_rut_can() {
    local han=${CHO_RUT_CAN:-1200} t=0 cho cham
    RUT_CAN_XONG=0
    printf '  chờ hàng đợi rút cạn'
    while [ "$t" -lt "$han" ]; do
        read -r cho cham _ <<< "$(trang_thai || echo '? ? ?')"
        if [ "$cho" = 0 ] && [ "$cham" = 0 ]; then echo " → rỗng sau ${t}s"; RUT_CAN_XONG=1; return; fi
        [ $((t % 30)) -eq 0 ] && printf ' [chờ %s · đang chấm %s]' "$cho" "$cham" || printf '.'
        sleep 3; t=$((t + 3))
    done
    echo; echo "  ⚠ Sau ${han}s vẫn chưa rỗng. R1 'chưa có verdict' sẽ KHÔNG đo được. Nới: CHO_RUT_CAN=2400"
}

# Một lượt đo đầy đủ: mốc id → k6 → rút cạn → số DB. $1 file k6, $2 tên json, còn lại là -e cho k6.
do_mot_luot() {
    local js=$1 ten=$2; shift 2
    local json="$RA/tom-tat-$ten.json" id_sau id_den t_k6 t_rut
    id_sau=$(db_id_max)
    k6 run -e BASE="$BASE" -e DE_MA="$DE_MA" -e SO_TAI_KHOAN="$SO_TAI_KHOAN" -e RA_JSON="$json" "$@" "$HERE/$js" 2>&1 | tee "$RA/$ten.log"
    local ma=${PIPESTATUS[0]}
    if [ "$ma" -ne 0 ] && [ "$ma" -ne 99 ]; then
        # Không kết luận cho một lượt không chạy trọn: 2026-09-11 lượt như thế vẫn in bảng "P1 0 ms ✅".
        echo "  ✗ k6 thoát mã $ma (không phải trượt ngưỡng): lượt đo không chạy trọn, KHÔNG có kết luận. Xem $RA/$ten.log" >&2
        return 1
    fi
    t_k6=$(db_now)
    cho_rut_can
    t_rut=$(db_now)
    id_den=$(db_id_max)       # cận trên: báo cáo chạy lại sau này không đếm lẫn lượt khác
    [ -f "$json" ] || { echo "  ✗ k6 không ghi $json — bỏ qua phần DB của lượt này."; return; }
    python3 "$HERE/bao-cao-db.py" --json "$json" --kich-ban "$KICH_BAN" --id-sau "$id_sau" --id-den "$id_den" \
        --rut-can-xong "$RUT_CAN_XONG" --t-k6-xong "$t_k6" --t-rut-can-xong "$t_rut" | tee -a "$RA/$ten.log"
}

# JVM chưa JIT, cache Postgres nguội: đo 2026-09-05 cho doc_ms p95 9ms → 3ms chỉ vì THỨ TỰ chạy.
khoi_dong() {
    [ "${KHOI_DONG:-45s}" = 0 ] && return
    echo; echo "══════════ khởi động ${KHOI_DONG:-45s} · KẾT QUẢ BỎ ĐI ══════════"
    k6 run --quiet -e BASE="$BASE" -e DE_MA="$DE_MA" -e NGUOI=50 -e THOI_LUONG="${KHOI_DONG:-45s}" -e DOC_LEN=10s \
        "$HERE/k6-tai.js" > "$RA/khoi-dong.log" 2>&1
    local ma=$?
    # Kết quả bỏ đi, LỖI thì không: 2026-09-11 lượt khởi động chết ở setup() mà không in ra chữ nào.
    if [ "$ma" -ne 0 ] && [ "$ma" -ne 99 ]; then
        grep -m3 -E 'level=error|Error' "$RA/khoi-dong.log" | cut -c1-200 | sed 's/^/    /' >&2
        loi "Lượt khởi động hỏng (k6 mã $ma) — lượt đo chính sẽ hỏng cùng lý do. Log: $RA/khoi-dong.log"
    fi
    cho_rut_can
}

case "$KICH_BAN" in
quet)
    khoi_dong
    CAC_MUC=${CAC_MUC:-"100 200 400 500 1000"}
    for n in $CAC_MUC; do
        [ "$n" -lt "$SO_TAI_KHOAN" ] || loi "Mức $n cần ≥ $((n + 1)) tài khoản, SO_TAI_KHOAN=$SO_TAI_KHOAN."
        echo; echo "══════════ $n người ảo ══════════"
        do_mot_luot k6-tai.js "$n" -e NGUOI="$n" -e THOI_LUONG="${THOI_LUONG:-3m}" || exit 1
        [ "$n" = "${CAC_MUC##* }" ] || { echo "  nghỉ ${NGHI:-60}s cho máy nguội (nfrplan 2.2: M1 Max throttle)"; sleep "${NGHI:-60}"; }
    done
    python3 "$HERE/tong-hop.py" "$RA" | tee "$RA/tong-hop.txt"
    ;;
on-dinh)
    khoi_dong
    do_mot_luot k6-on-dinh.js on-dinh -e TOC_DO="${TOC_DO:-2}" -e THOI_LUONG="${THOI_LUONG:-30m}" || exit 1
    ;;
dot-bien)
    khoi_dong
    # Tài khoản của lượt khởi động cũng nằm trong đợt dồn: phải qua rate limit 10s của chúng.
    sleep 11
    do_mot_luot k6-dot-bien.js dot-bien -e SO_BAI="${SO_BAI:-500}" || exit 1
    ;;
ky-thi)
    SO_NGUOI=${SO_NGUOI:-1000} THOI_LUONG=${THOI_LUONG:-3m}
    [ "$SO_NGUOI" -le "$SO_TAI_KHOAN" ] || loi "SO_NGUOI=$SO_NGUOI cần ngần ấy tài khoản, SO_TAI_KHOAN=$SO_TAI_KHOAN."
    giay=$(python3 -c 'import re, sys; m = re.fullmatch(r"(\d+)(s|m)", sys.argv[1]); print(int(m[1]) * (60 if m[2] == "m" else 1) if m else "")' "$THOI_LUONG")
    [ -n "$giay" ] || loi "THOI_LUONG phải dạng 180s hoặc 3m, không phải '$THOI_LUONG'."
    # Kết nối mở ĐẦU TIÊN phải sống qua: chờ mở hết (≤ 30s) + k6 khởi động + THOI_LUONG + rút cạn + 15s
    # để thấy bài AC cuối lên bảng. Và phải dưới oj.sse.timeout 300s: quá mốc ấy máy chủ tự đóng, tính là đứt.
    giu=$((giay + 75)) phut=$((giay / 60 + 12))
    [ "$giu" -le 285 ] || loi "THOI_LUONG=$THOI_LUONG quá dài: kết nối SSE cần giữ ${giu}s, vượt oj.sse.timeout 300s. Tối đa 3m30s."
    khoi_dong
    contest_id=$("${PSQL[@]}" -X -q -tA -v xac_nhan_db="$OJ_TAI_DB" -v de_ma="$DE_MA" -v phut="$phut" \
        < "$HERE/seed-ky-thi.sql" 2>"$RA/seed-ky-thi.err" | tail -1)
    [ -n "$contest_id" ] || loi "seed-ky-thi.sql hỏng: $(tail -2 "$RA/seed-ky-thi.err")"
    echo "  kỳ thi đo: id $contest_id · đề $DE_MA · tự kết thúc sau $phut phút"
    PHIEN=$(mktemp "${TMPDIR:-/tmp}/oj-phien.XXXXXX")
    id_sau=$(db_id_max)
    python3 "$HERE/sse-tai.py" --kich-ban ky-thi --base "$BASE" --contest-id "$contest_id" \
        --so-ket-noi "$SO_NGUOI" --dang-nhap --phien-ra "$PHIEN" --san-sang "$RA/san-sang" \
        --su-kien-ra "$RA/su-kien-sse.json" --giu "$giu" --json "$RA/tom-tat-sse.json" > "$RA/sse.log" 2>&1 &
    PID_SSE=$!
    printf '  sse-tai.py: đăng nhập %s tài khoản rồi mở %s kết nối' "$SO_NGUOI" "$SO_NGUOI"
    han=$((SO_NGUOI / 2 + 180)) t=0
    until [ -f "$RA/san-sang" ]; do
        kill -0 "$PID_SSE" 2>/dev/null || { echo; tail -5 "$RA/sse.log" >&2; loi "sse-tai.py dừng trước khi mở xong. Log: $RA/sse.log"; }
        [ "$t" -lt "$han" ] || { echo; loi "Sau ${han}s sse-tai.py vẫn chưa mở xong kết nối. Log: $RA/sse.log"; }
        sleep 3; t=$((t + 3)); printf '.'
    done
    echo; grep -E 'đăng nhập|mở xong' "$RA/sse.log" | sed 's/^ */  /'
    json="$RA/tom-tat-ky-thi.json"
    k6 run -e BASE="$BASE" -e DE_MA="$DE_MA" -e PHIEN="$PHIEN" -e TOC_DO="${TOC_DO:-3}" -e THOI_LUONG="$THOI_LUONG" \
        -e RA_JSON="$json" "$HERE/k6-ky-thi.js" 2>&1 | tee "$RA/ky-thi.log"
    ma=${PIPESTATUS[0]}
    rm -f "$PHIEN"
    [ "$ma" -eq 0 ] || [ "$ma" -eq 99 ] || loi "k6 thoát mã $ma: lượt đo không chạy trọn, KHÔNG có kết luận. Xem $RA/ky-thi.log"
    t_k6=$(db_now); cho_rut_can; t_rut=$(db_now); id_den=$(db_id_max)
    echo "  chờ sse-tai.py giữ nốt kết nối (${giu}s kể từ lúc mở)..."
    wait "$PID_SSE"; PID_SSE=''
    sed -n '/══/,$p' "$RA/sse.log"
    python3 "$HERE/bao-cao-db.py" --json "$json" --kich-ban ky-thi --id-sau "$id_sau" --id-den "$id_den" \
        --rut-can-xong "$RUT_CAN_XONG" --t-k6-xong "$t_k6" --t-rut-can-xong "$t_rut" \
        --sse-json "$RA/tom-tat-sse.json" --su-kien-sse "$RA/su-kien-sse.json" | tee -a "$RA/ky-thi.log"
    ;;
sse)
    : "${CONTEST_ID:?Thiếu CONTEST_ID — stream bảng xếp hạng cần một kỳ thi có thật.}"
    curl -fsS --max-time 5 "$BASE/api/v1/contests/$CONTEST_ID/standings" >/dev/null \
        || loi "Kỳ thi $CONTEST_ID không tồn tại trên $BASE."
    python3 "$HERE/sse-tai.py" --base "$BASE" --contest-id "$CONTEST_ID" \
        --so-ket-noi "${SO_KET_NOI:-1000}" --giu "${GIU:-180}" --json "$RA/tom-tat-sse.json" | tee "$RA/sse.log"
    ;;
esac

echo
echo "Kết quả: $RA/"
case "$host" in localhost|127.*|\[::1\])
    echo "⚠ k6/sse chạy trên CHÍNH máy được đo — số bi quan hơn sự thật, không dùng để nghiệm thu từ ~400 người ảo." ;; esac
echo "Kiểm worker chạy isolate thật (memory_kb khác nhau từng dòng):"
echo "  ${PSQL[*]} -c 'SELECT memory_kb, time_ms FROM judge_runs ORDER BY submission_id DESC LIMIT 5'"
[ "$KICH_BAN" = sse ] || echo "Dọn: ${PSQL[*]} -v xac_nhan_db=$OJ_TAI_DB < $HERE/don-dep.sql"
case "$host" in localhost|127.*|10.*|192.168.*|*.local|\[::1\]) ;; *)
    echo "Tên miền đo đang MỞ ra Internet — tắt khi xong: $HERE/tunnel-do.sh --xoa" ;; esac
