#!/usr/bin/env bash
# =============================================================================
# Quét năm mức 100 → 200 → 400 → 500 → 1000, có nghỉ giữa các mức.
#
#   ./scripts/tai-trong/chay.sh
#   BASE=http://may-thu:8080 THOI_LUONG=5m ./scripts/tai-trong/chay.sh
#   CAC_MUC="100 200" ./scripts/tai-trong/chay.sh        # chạy thử nhanh
#
# ★ VÌ SAO PHẢI NGHỈ GIỮA CÁC MỨC — HAI LÝ DO, KHÔNG PHẢI MỘT
#
# 1. HÀNG ĐỢI. Mức 100 để lại một hàng đợi chưa rút cạn. Chạy mức 200 ngay sau
#    đó là đo mức 200 CỘNG phần thừa của mức 100, và các con số sẽ không so
#    được với nhau. Vòng chờ dưới đây hỏi `GET /api/v1/status` tới khi
#    `dangCho` về 0.
#
# 2. NHIỆT — cái này riêng của M1 Max và nfrplan 2.2 đã cảnh báo. Máy chạy full
#    core 10–15 phút liên tục sẽ throttle. Quét năm mức mất ~35 phút, nghĩa là
#    mức 1000 được đo trên một cái máy CHẬM HƠN cái máy đã đo mức 100 — và khi
#    đó bảng tổng hợp sẽ đổ tội cho phần mềm một chuyện của phần cứng. `NGHI`
#    (mặc định 60s) là thời gian để quạt kịp làm việc. Đừng đặt về 0.
# =============================================================================
set -uo pipefail        # KHÔNG có -e: xem ghi chú ở vòng lặp chính bên dưới

BASE=${BASE:-http://localhost:8080}
THOI_LUONG=${THOI_LUONG:-3m}
CAC_MUC=${CAC_MUC:-"100 200 400 500 1000"}
NGHI=${NGHI:-60}
RA=${RA:-/tmp/oj-tai-trong}
HERE=$(cd "$(dirname "$0")" && pwd)

command -v k6 >/dev/null || {
    echo "Chưa có k6. Cài:  https://k6.io/docs/get-started/installation/"
    echo "  macOS:              brew install k6"
    echo "  Debian/Ubuntu/WSL:  sudo gpg -k && \\"
    echo "    sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg \\"
    echo "      --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69 && \\"
    echo "    echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main' \\"
    echo "      | sudo tee /etc/apt/sources.list.d/k6.list && sudo apt update && sudo apt install k6"
    exit 1
}

curl -fsS "$BASE/api/v1/status" >/dev/null || {
    echo "Không gọi được $BASE/api/v1/status — máy chủ chưa chạy?"
    exit 1
}

MUC_MAX=0
for n in $CAC_MUC; do [ "$n" -gt "$MUC_MAX" ] && MUC_MAX=$n; done

# -----------------------------------------------------------------------------
# Tiền kiểm MÁY ĐO. Một máy đo hụt hơi cho ra số xấu mà trông y hệt máy chủ hụt
# hơi, và đó là cách tệ nhất để mất một buổi chiều.
# -----------------------------------------------------------------------------
# macOS mặc định `ulimit -n` = 256. Mỗi VU giữ ít nhất một socket, cộng file
# tạm của k6 — 1000 VU sẽ đâm vào trần này và k6 báo "too many open files",
# một lỗi trông rất giống lỗi mạng.
CAN_FD=$((MUC_MAX * 4 + 1024))
if [ "$(ulimit -n)" -lt "$CAN_FD" ]; then
    for thu in 1048576 262144 65536 "$CAN_FD"; do
        ulimit -n "$thu" 2>/dev/null && break
    done
fi
[ "$(ulimit -n)" -lt "$CAN_FD" ] && {
    echo "⚠ ulimit -n = $(ulimit -n), cần ≥ $CAN_FD cho $MUC_MAX VU."
    echo "  Nâng trần cứng rồi chạy lại:  sudo launchctl limit maxfiles 262144 524288"
}

if [ "$(uname -s)" = "Darwin" ]; then
    # Hàng đợi accept của kernel. Mặc định 128 — lúc dốc lên 1000 VU, phần
    # connection tràn ra bị RESET, và k6 đếm chúng vào http_req_failed. Máy chủ
    # không hề lỗi; cái nghẽn nằm ở kernel của máy ĐO lẫn máy CHỦ.
    smc=$(sysctl -n kern.ipc.somaxconn 2>/dev/null || echo 128)
    [ "$smc" -lt 1024 ] && {
        echo "⚠ kern.ipc.somaxconn = $smc (mặc định macOS). Nâng trước khi đo ≥ 400 VU:"
        echo "    sudo sysctl -w kern.ipc.somaxconn=1024"
    }
fi

case "$BASE" in
    *localhost*|*127.0.0.1*|*::1*)
        [ "$MUC_MAX" -ge 400 ] && {
            echo
            echo "⚠ k6 sẽ chạy trên CHÍNH máy được đo, và mức cao nhất là $MUC_MAX VU."
            echo "  nfrplan 2.2 chỉ chừa 3 core cho macOS + Postgres + Redis + RabbitMQ + JVM."
            echo "  Từ ~400 VU, k6 ăn 1–2 core trong đúng 3 core ấy. Con số thu được sẽ là"
            echo "  con số của một máy chủ NHỎ HƠN máy chủ thật — bi quan, không phải sai,"
            echo "  nhưng cũng không dùng để nghiệm thu được."
            echo "  Muốn số dùng được ở 400+: chạy k6 từ máy khác, BASE=http://<ip-mac>:8080"
        };;
esac

echo
echo "⚠ Load test GHI DỮ LIỆU THẬT vào database sau $BASE."
echo "  Nó tạo hàng nghìn bài nộp và làm bẩn bảng xếp hạng. Dọn bằng don-dep.sql."
echo "  Các mức: $CAC_MUC · mỗi mức $THOI_LUONG · nghỉ ${NGHI}s giữa các mức."
read -r -p "  Gõ 'dong y' để chạy: " tra_loi
[ "$tra_loi" = "dong y" ] || { echo "Đã huỷ."; exit 1; }

mkdir -p "$RA"

cho_rut_can() {
    printf '  chờ hàng đợi rút cạn'
    for _ in $(seq 1 180); do
        n=$(curl -fsS "$BASE/api/v1/status" 2>/dev/null \
            | grep -o '"dangCho":[0-9]*' | cut -d: -f2) || true
        [ "${n:-1}" = "0" ] && { echo " → rỗng"; return; }
        printf '.'
        sleep 2
    done
    echo " → VẪN CÒN ${n:-?} bài. Con số của mức sau sẽ bị nhiễu."
}

# -----------------------------------------------------------------------------
# ★ VÌ SAO KHÔNG CÓ `set -e`
# k6 thoát với mã 99 khi một threshold trượt. Với `set -e` + `pipefail`, mức đầu
# tiên không đạt sẽ giết cả vòng quét — tức là kịch bản tự huỷ đúng vào lúc nó
# bắt đầu có ích. Trượt ngưỡng ở đây là DỮ LIỆU, không phải sự cố.
# -----------------------------------------------------------------------------
for n in $CAC_MUC; do
    echo
    echo "══════════ $n người ảo ══════════"
    k6 run -e BASE="$BASE" -e NGUOI="$n" -e THOI_LUONG="$THOI_LUONG" \
           -e RA_JSON="$RA/tom-tat-$n.json" \
           "$HERE/k6-tai.js" 2>&1 | tee "$RA/$n.log"
    ma=${PIPESTATUS[0]}
    [ "$ma" -eq 0 ] || [ "$ma" -eq 99 ] \
        || echo "  ⚠ k6 thoát với mã $ma (không phải trượt ngưỡng) — xem $RA/$n.log"
    cho_rut_can
    [ "$n" = "${CAC_MUC##* }" ] || { echo "  nghỉ ${NGHI}s cho máy nguội"; sleep "$NGHI"; }
done

python3 "$HERE/tong-hop.py" "$RA" || {
    echo "Không chạy được tong-hop.py — các file JSON vẫn nằm ở $RA/"
}
echo "Chi tiết: $RA/  ·  Dọn dữ liệu: psql <migrator> -f $HERE/don-dep.sql"
