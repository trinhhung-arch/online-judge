#!/usr/bin/env bash
# =============================================================================
# Tên miền ĐO qua Cloudflare — để kịch bản ky-thi đi đúng đường người dùng thật đi:
#   Internet → Cloudflare → tunnel → API, mà KHÔNG chạm prod.
#
#   ./scripts/tai-trong/tunnel-do.sh do.onlinejudge67.click    dựng (lần đầu: tạo tunnel + DNS) rồi chạy
#   ./scripts/tai-trong/tunnel-do.sh --xoa                       tắt
#
# ★ VÌ SAO KHÔNG ĐO THẲNG TÊN MIỀN CHÍNH: nó là API prod trên ojdb_prod. Load test ở đó là 1000 tài khoản
#   có hash mật khẩu công khai trong git, hàng nghìn bài nộp giả và một kỳ thi giả trong database thật.
#   Tên miền con này trỏ vào STACK ĐO (cổng 18080, database ojdb) — dựng bằng dung-stack-do.sh.
#
# ★ TUNNEL RIÊNG "oj-do", KHÔNG SỬA ~/.cloudflared/config.yml. Thêm luật vào tunnel prod là phải khởi
#   động lại cloudflared của prod (site thật rớt kết nối), và một lần sai thứ tự luật là mở /internal
#   của prod. Cái giá: đo một cloudflared THỨ HAI trên cùng máy — cùng phần mềm, cùng đường Cloudflare.
#
# ★ DNS PHẢI TRỎ BẰNG CẤU HÌNH CỦA TUNNEL ĐO. Đo 2026-09-11: `cloudflared tunnel route dns oj-do <tên>`
#   đọc ~/.cloudflared/config.yml (tunnel: <id PROD>) và trỏ bản ghi vào TUNNEL PROD — tên miền đo trả
#   404 từ luật bắt-tất-cả của prod, kể cả khi tunnel đo đã tắt. Nên: --config của tunnel đo, id thay
#   cho tên, đối chiếu tunnelID trong kết quả, và trước khi mở phải thấy 530 (Cloudflare nhận ra đúng
#   tunnel đo, đang tắt). 404 hay 200 lúc ấy là đang đi vào một tunnel khác.
#
# ★ PHẢI CHẶN NGƯỜI LẠ. Stack đo chạy profile dev: Turnstile tắt, tài khoản tai-* có mật khẩu ai đọc repo
#   cũng biết. Luật WAF phải chứa CẢ IPv4 LẪN dải IPv6 /64 của máy đo: macOS đổi địa chỉ IPv6 tạm thời,
#   còn k6 và Python chọn họ địa chỉ theo cách riêng. Đo 2026-09-11: luật chỉ có một địa chỉ IPv6, và
#   request IPv4 của chính máy này vẫn KHÔNG bị chặn — tức luật đó không chặn ai cả (chưa Deploy).
#
# ★ ĐO TỪ CHÍNH MÁY NÀY thì dữ liệu ra Cloudflare rồi quay về: băng thông nhà bị dùng CẢ HAI CHIỀU.
# =============================================================================
set -uo pipefail
# ★ KHÔNG `| grep -q` với pipefail — xem đầu dung-stack-do.sh.
NHA=${OJ_STACK_DO_DIR:-$HOME/oj-stack-do}
GOC=$(cd "$(dirname "$0")/../.." && pwd)
TEN=oj-do CONG_API=18080
CAU_HINH=$NHA/cloudflared-do.yml PID=$NHA/cloudflared-do.pid LOG=$NHA/cloudflared-do.log
loi() { echo "✗ $*" >&2; exit 1; }
ok()  { echo "✓ $*"; }
dang_chay() { [ -f "$PID" ] && ps -p "$(cat "$PID")" >/dev/null 2>&1; }
id_tunnel() {
    cloudflared tunnel list --output json 2>/dev/null | python3 -c \
        'import json, sys; print(next((t["id"] for t in json.load(sys.stdin) if t["name"] == sys.argv[1]), ""))' "$TEN" 2>/dev/null
}
ip_cong_khai() { curl "-$1" -fsS --max-time 8 https://www.cloudflare.com/cdn-cgi/trace 2>/dev/null | sed -n 's/^ip=//p'; }
ma_http() { curl "-$1" -s -o /dev/null -w '%{http_code}' --max-time 8 "https://$MIEN/api/v1/status" 2>/dev/null; }

# Tunnel đo đang TẮT: đúng thì Cloudflare trả 530 (thấy tunnel, không có kết nối) cho mọi họ địa chỉ của máy này.
kiem_truoc_khi_mo() {
    local v ip m
    for v in 4 6; do
        ip=$([ "$v" = 4 ] && echo "$ip4" || echo "$ip6")
        [ -n "$ip" ] || continue
        # Chờ tới khi ra 530 hoặc 403. 404/200 ngay sau khi vừa GHI ĐÈ bản ghi DNS là edge chưa cập nhật —
        # đo 2026-09-11: lượt đầu sau --overwrite-dns ra 404, chạy lại ngay sau đó ra 530.
        for _ in $(seq 20); do m=$(ma_http "$v"); case "$m" in 530|403) break ;; esac; sleep 3; done
        case "$m" in
            530) ok "IPv$v → 530: Cloudflare đưa $MIEN tới tunnel đo (đang tắt), và luật WAF cho máy này qua" ;;
            403) loi "IPv$v → 403: luật WAF đang chặn CHÍNH máy này. Biểu thức phải chứa $ip." ;;
            404|200) loi "IPv$v → $m: $MIEN đang đi vào một tunnel KHÁC đang chạy (thường là tunnel prod), không phải '$TEN'." ;;
            *) loi "IPv$v → $m, mong đợi 530." ;;
        esac
    done
}

if [ "${1:-}" = --xoa ]; then
    if dang_chay; then kill "$(cat "$PID")"; ok "Đã tắt tunnel đo (PID $(cat "$PID")) — tên miền đo giờ trả lỗi 1033 của Cloudflare."
    else echo "  tunnel đo không chạy"; fi
    rm -f "$PID"
    echo "  Tunnel '$TEN' và bản ghi DNS được giữ để lần sau dùng lại. Gỡ hẳn: cloudflared tunnel delete $TEN + xoá CNAME trong dashboard."
    exit 0
fi

MIEN=${1:-}
[ -n "$MIEN" ] || loi "Dùng: $0 <tên-miền-đo>   ví dụ: $0 do.onlinejudge67.click   ·   tắt: $0 --xoa"
MIEN=${MIEN#https://}; MIEN=${MIEN#http://}; MIEN=${MIEN%%/*}
VUNG=${MIEN#*.}
grep -E "hostname:[[:space:]]*${MIEN//./\\.}[[:space:]]*$" "$HOME/.cloudflared/config.yml" >/dev/null 2>&1 \
    && loi "$MIEN thuộc tunnel PROD (~/.cloudflared/config.yml). Tên miền đo phải là một tên miền con RIÊNG."
command -v cloudflared >/dev/null || loi "Chưa có cloudflared."
curl -fsS --max-time 5 "http://127.0.0.1:$CONG_API/api/v1/status" >/dev/null 2>&1 \
    || loi "Stack đo chưa chạy ở 127.0.0.1:$CONG_API. Dựng trước: $GOC/scripts/tai-trong/dung-stack-do.sh"
dang_chay && { ok "Tunnel đo đã chạy (PID $(cat "$PID")). Tắt: $0 --xoa"; exit 0; }
mkdir -p "$NHA"

echo "── 1 · Tunnel '$TEN' ──"
uuid=$(id_tunnel)
if [ -z "$uuid" ]; then
    cloudflared tunnel create "$TEN" || loi "Không tạo được tunnel '$TEN'."
    uuid=$(id_tunnel)
fi
[ -n "$uuid" ] && [ -f "$HOME/.cloudflared/$uuid.json" ] \
    || loi "Không thấy khoá ~/.cloudflared/${uuid:-<id>}.json của tunnel '$TEN' trên máy này."
ok "tunnel $uuid"

echo "── 2 · Cấu hình — CÙNG luật chặn với tunnel prod ──"
cat > "$CAU_HINH" <<EOF
# Sinh bởi tunnel-do.sh — đừng sửa tay. Thứ tự là ngữ nghĩa: luật chặn đứng TRƯỚC luật cho qua.
tunnel: $uuid
credentials-file: $HOME/.cloudflared/$uuid.json
loglevel: info
ingress:
  - hostname: $MIEN
    path: ^/internal(/|\$)
    service: http_status:404
  - hostname: $MIEN
    path: ^/actuator(/|\$)
    service: http_status:404
  - hostname: $MIEN
    service: http://127.0.0.1:$CONG_API
  - service: http_status:404
EOF
cloudflared tunnel --config "$CAU_HINH" ingress validate >/dev/null 2>&1 || loi "Cấu hình tunnel đo không hợp lệ: $CAU_HINH"
for p in /internal/judge/claim /actuator/health /api/v1/status; do
    printf '  %-22s → %s\n' "$p" "$(cloudflared tunnel --config "$CAU_HINH" ingress rule "https://$MIEN$p" 2>&1 | sed -n 's/^[[:space:]]*service:[[:space:]]*//p' | head -1)"
done

echo "── 3 · DNS $MIEN → tunnel '$TEN' ──"
ra=$(cloudflared tunnel --config "$CAU_HINH" route dns --overwrite-dns "$uuid" "$MIEN" 2>&1)
echo "$ra" | grep "tunnelID=$uuid" >/dev/null \
    || loi "DNS $MIEN KHÔNG trỏ về tunnel đo. cloudflared nói: $(echo "$ra" | tail -1)"
ok "$(echo "$ra" | tail -1 | sed -E 's/^[0-9TZ:.-]+ [A-Z]{3} //')"

echo "── 4 · BẮT BUỘC: chặn mọi IP khác ──"
ip4=$(ip_cong_khai 4) ip6=$(ip_cong_khai 6)
[ -n "$ip4$ip6" ] || loi "Không lấy được IP công khai của máy này."
dai6=$([ -n "$ip6" ] && python3 -c 'import ipaddress, sys; print(ipaddress.ip_network(sys.argv[1] + "/64", strict=False))' "$ip6")
tap=$(echo "$ip4 $dai6" | xargs)
cat <<EOF
  Cloudflare dashboard → $VUNG → Security → WAF → Custom rules → tạo mới (hoặc SỬA luật cũ)
    Tên:        oj-do chỉ máy đo
    Biểu thức:  (http.host eq "$MIEN" and not ip.src in {$tap})
    Hành động:  Block        → bấm DEPLOY, không phải "Save as draft"
  Máy này: IPv4 ${ip4:-không có} · IPv6 ${ip6:-không có} (lấy cả dải /64 vì macOS đổi địa chỉ IPv6 tạm thời).
EOF
read -r -p "  Gõ 'da chan' khi luật đã Deploy: " tl
[ "$tl" = "da chan" ] || loi "Chưa mở tunnel đo."

echo "── 5 · Kiểm TRƯỚC khi mở ──"
kiem_truoc_khi_mo

echo "── 6 · Chạy và kiểm từ ngoài vào ──"
nohup cloudflared tunnel --config "$CAU_HINH" run "$TEN" > "$LOG" 2>&1 &
echo $! > "$PID"
for _ in $(seq 30); do grep 'Registered tunnel connection' "$LOG" >/dev/null && break; sleep 1; done
grep 'Registered tunnel connection' "$LOG" >/dev/null || { kill "$(cat "$PID")"; rm -f "$PID"; tail -5 "$LOG" >&2; loi "Tunnel đo không lên sau 30s. Log: $LOG"; }
for _ in $(seq 20); do [ "$(ma_http 6)" = 200 ] || [ "$(ma_http 4)" = 200 ] && break; sleep 3; done
"$GOC/scripts/kiem-tunnel.sh" "$MIEN" || { kill "$(cat "$PID")"; rm -f "$PID"; loi "Tunnel đo KHÔNG đạt kiểm — đã TẮT lại. Log: $LOG"; }
cat <<EOF

✓ Tunnel đo ĐANG MỞ: https://$MIEN → stack đo. ĐỂ NGUYÊN nó chạy suốt lượt đo.

  Việc tiếp theo, theo thứ tự:
    1. Kiểm luật WAF từ ngoài: điện thoại TẮT wifi, dùng 4G, mở https://$MIEN/api/v1/status
       → phải thấy trang chặn của Cloudflare. Thấy {"dangCho":...} là luật chưa chạy: tắt ngay ($0 --xoa).
    2. docker pause oj-worker
    3. BASE=https://$MIEN CHO_PHEP_DICH_CONG_KHAI=https://$MIEN $GOC/scripts/tai-trong/chay.sh ky-thi
    4. docker unpause oj-worker
    5. CHỈ KHI ĐO XONG mới tắt tunnel: $0 --xoa   (dung-stack-do.sh --xoa cũng tắt nó)
EOF
