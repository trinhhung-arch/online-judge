#!/usr/bin/env bash
# =============================================================================
# Đo ba mức 100 → 500 → 1000, có nghỉ giữa các mức.
#
#   ./scripts/tai-trong/chay.sh
#   BASE=http://may-thu:8080 THOI_LUONG=5m ./scripts/tai-trong/chay.sh
#
# ★ VÌ SAO PHẢI NGHỈ GIỮA CÁC MỨC
# Mức 100 để lại một hàng đợi chưa rút cạn. Chạy mức 500 ngay sau đó là đo mức
# 500 CỘNG phần thừa của mức 100, và ba con số sẽ không so được với nhau. Vòng
# chờ dưới đây hỏi `GET /api/v1/status` tới khi `dangCho` về 0.
# =============================================================================
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
THOI_LUONG=${THOI_LUONG:-3m}
CAC_MUC=${CAC_MUC:-"100 500 1000"}
RA=${RA:-/tmp/oj-tai-trong}
HERE=$(cd "$(dirname "$0")" && pwd)

command -v k6 >/dev/null || {
    echo "Chưa có k6. Cài:  https://k6.io/docs/get-started/installation/"
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

echo "⚠ Load test GHI DỮ LIỆU THẬT vào database sau $BASE."
echo "  Nó tạo hàng nghìn bài nộp và làm bẩn bảng xếp hạng. Dọn bằng don-dep.sql."
read -r -p "  Gõ 'dong y' để chạy: " tra_loi
[ "$tra_loi" = "dong y" ] || { echo "Đã huỷ."; exit 1; }

mkdir -p "$RA"

cho_rut_can() {
    echo -n "  chờ hàng đợi rút cạn"
    for _ in $(seq 1 180); do
        n=$(curl -fsS "$BASE/api/v1/status" | grep -o '"dangCho":[0-9]*' | cut -d: -f2)
        [ "${n:-1}" = "0" ] && { echo " → rỗng"; return; }
        echo -n "."
        sleep 2
    done
    echo " → VẪN CÒN $n bài. Con số của mức sau sẽ bị nhiễu."
}

for n in $CAC_MUC; do
    echo
    echo "══════════ $n người ảo ══════════"
    k6 run -e BASE="$BASE" -e NGUOI="$n" -e THOI_LUONG="$THOI_LUONG" \
           --summary-export "$RA/tom-tat-$n.json" \
           "$HERE/k6-tai.js" 2>&1 | tee "$RA/$n.log"
    cho_rut_can
done

echo
echo "══════════ Tổng hợp ══════════"
printf '%-8s %-12s %-12s %-12s %-12s\n' người doc_p95 nop_p95 verdict_p95 hàng_đợi_max
for n in $CAC_MUC; do
    f="$RA/tom-tat-$n.json"
    [ -f "$f" ] || continue
    python3 - "$f" "$n" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])).get('metrics', {})
def g(ten, k='p(95)'):
    v = d.get(ten, {}).get(k)
    return f'{v:.0f}' if isinstance(v, (int, float)) else '—'
print(f"{sys.argv[2]:<8} {g('doc_ms'):<12} {g('nop_ms'):<12} "
      f"{g('verdict_ms'):<12} {g('hang_doi','max'):<12}")
PY
done
echo
echo "Chi tiết: $RA/  ·  Dọn dữ liệu: psql <migrator> -f $HERE/don-dep.sql"
