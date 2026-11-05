#!/usr/bin/env bash
set -uo pipefail

MIEN=${1:-}
[ -n "$MIEN" ] || { echo "Dùng: $0 <tên-miền>   ví dụ: $0 oj.vi-du.com" >&2; exit 2; }
MIEN=${MIEN#https://}; MIEN=${MIEN#http://}; MIEN=${MIEN%%/*}

loi() { echo "✗ $*" >&2; hong=$((hong + 1)); }
ok()  { echo "✓ $*"; }
hong=0

ma() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 --path-as-is "https://$MIEN$1" 2>/dev/null
}

BIEN_THE_INTERNAL="//internal/judge/claim /./internal/judge/claim
/api/v1/../../internal/judge/claim /internal;x=1/judge/claim /%2e/internal/judge/claim
/api/v1/%2e%2e/%2e%2e/internal/judge/claim"
BIEN_THE_ACTUATOR="//actuator/health /./actuator/health /actuator;x=1/health"

echo "── Kiểm tunnel https://$MIEN ──"
echo

m=$(ma /api/v1/status)
if [ "$m" = 200 ]; then ok "/api/v1/status → 200 (tunnel sống, bề mặt công khai chạy)"
else
    echo "✗ /api/v1/status → $m, mong đợi 200." >&2
    if [ "$m" = 000 ]; then
        echo "     Không kết nối được tới https://$MIEN — DNS chưa trỏ, tunnel chưa lên," >&2
        echo "     hoặc gõ sai tên miền." >&2
    else
        echo "     Tunnel lên nhưng oj-api không trả lời." >&2
    fi
    echo >&2
    echo "     DỪNG Ở ĐÂY. Chạy tiếp thì mọi ô dưới đều 000, và một ô 000 KHÔNG có nghĩa" >&2
    echo "     là 'đã chặn được' — nó có nghĩa là chưa đo được gì. Hai thứ đó nhìn giống" >&2
    echo "     nhau trong một bảng toàn dấu ✗." >&2
    exit 1
fi

for p in /internal/judge/claim /internal/judge/result /internal/judge/progress \
         /internal/judge/benchmark /internal/judge/testdata $BIEN_THE_INTERNAL; do
    m=$(ma "$p")
    case "$m" in
        404) ok "$p → 404" ;;
        401|403) loi "$p → $m — CẢ HAI LỚP ĐẦU THỦNG.
     $m nghĩa là request ĐÃ tới bước kiểm secret: luật ingress để lọt, VÀ oj-api không nhận
     ra request đi qua Cloudflare. Thứ duy nhất còn lại là shared secret. Sửa ingress theo
     infra/cloudflared/config.yml (luật /internal phải nằm TRƯỚC luật bắt-tất-cả), và kiểm
     oj-api đang chạy bản có InternalSecretFilter.DAU_CLOUDFLARE." ;;
        000) loi "$p → không kết nối được. KHÔNG phải 'đã chặn' — là chưa đo được." ;;
        *) loi "$p → $m — không phải 404. Đọc ingress." ;;
    esac
done

CAU_HINH=${CLOUDFLARED_CONFIG:-$HOME/.cloudflared/config.yml}
if command -v cloudflared >/dev/null && [ -f "$CAU_HINH" ]; then
    if cloudflared tunnel --config "$CAU_HINH" ingress rule "https://$MIEN/api/v1/status" 2>/dev/null \
            | grep -q 'http_status:404'; then
        loi "ingress: $CAU_HINH không đưa $MIEN/api/v1/status tới đâu — đây không phải cấu hình
     của tunnel phục vụ $MIEN, các dòng 2b dưới đây vô nghĩa. Đặt CLOUDFLARED_CONFIG=<file ấy>."
    fi
    for p in /internal/judge/claim $BIEN_THE_INTERNAL /actuator/health $BIEN_THE_ACTUATOR; do
        if cloudflared tunnel --config "$CAU_HINH" ingress rule "https://$MIEN$p" 2>/dev/null \
                | grep -q 'http_status:404'; then
            ok "ingress: $p → http_status:404"
        else
            loi "ingress: $p KHÔNG khớp luật chặn — lớp 1 thủng (lớp sau có thể vẫn giữ,
     nhưng đừng để một lớp gánh việc của hai). Chép hai luật 'path:' từ
     infra/cloudflared/config.yml vào $CAU_HINH, rồi khởi động lại cloudflared."
        fi
    done
else
    echo "  ! Bỏ qua mục 2b — thiếu cloudflared hoặc $CAU_HINH. Lớp 1 CHƯA được đo riêng;"
    echo "    đặt CLOUDFLARED_CONFIG=<đường dẫn> nếu file cấu hình nằm chỗ khác."
fi

for p in /actuator/health /actuator/env /actuator/configprops $BIEN_THE_ACTUATOR; do
    m=$(ma "$p")
    if [ "$m" = 404 ]; then ok "$p → 404"
    elif [ "$m" = 000 ]; then loi "$p → không kết nối được, chưa đo được gì."
    else loi "$p → $m — actuator lộ ra internet.
     Nó bật show-details: always, tức là công bố tình trạng từng thành phần hạ tầng.
     Kiểm cả hai: management.server.port vẫn là 8081, VÀ ingress không có dòng nào
     trỏ về localhost:8081."
    fi
done

h=$(curl -sI --max-time 10 "https://$MIEN/" 2>/dev/null)
echo "$h" | grep -qi '^content-security-policy:' \
    && ok "Content-Security-Policy sống sót qua Cloudflare" \
    || loi "Không thấy Content-Security-Policy. SecurityHeadersFilter không chạy, hoặc bị gỡ ở giữa."
echo "$h" | grep -qi '^strict-transport-security:' \
    && ok "HSTS đã bật" \
    || echo "  ! HSTS chưa bật — đặt OJ_HSTS_MAX_AGE=31536000 rồi khởi động lại oj-api.
    Chỉ bật khi CHẮC CHẮN tên miền này sẽ luôn có HTTPS: trình duyệt nhớ rất lâu."
h=$(curl -s -D - -o /dev/null --max-time 10 "https://$MIEN/api/v1/status" 2>/dev/null)
echo "$h" | grep -qi '^cache-control: *no-store' \
    && ok "/api/v1 mang Cache-Control: no-store qua Cloudflare" \
    || loi "/api/v1/status thiếu 'Cache-Control: no-store' (thấy: $(echo "$h" | grep -i '^cache-control:' | tr -d '\r' || echo 'không có header ấy')).
     oj-api chưa chạy bản có KhongLuuDemApiFilter, hoặc Cloudflare đang ghi đè header (Browser Cache TTL / Cache Rule)."

m=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://$MIEN/" 2>/dev/null || echo 000)
case "$m" in
    301|302|307|308) ok "http:// → chuyển hướng $m sang https" ;;
    000) echo "  ! Không gọi được http:// — nhiều khả năng đúng, Cloudflare đã chặn sẵn." ;;
    *) loi "http:// trả $m mà không chuyển hướng. Bật 'Always Use HTTPS' trong Cloudflare." ;;
esac

bat_tay() { echo | openssl s_client "-$1" -connect "$MIEN:443" -servername "$MIEN" 2>&1; }
xong() { [[ $1 =~ Cipher\ is\ [A-Z0-9] ]]; }
if command -v openssl >/dev/null; then
    r12=$(bat_tay tls1_2) r11=$(bat_tay tls1_1)
    if ! xong "$r12"; then
        echo "  ! openssl không bắt tay được TLS 1.2 — mục TLS CHƯA đo được."
    elif xong "$r11"; then
        loi "Biên Cloudflare vẫn nhận TLS 1.1. Dashboard → SSL/TLS → Edge Certificates →
     Minimum TLS Version = TLS 1.2, rồi TẢI LẠI trang xem ô đã giữ giá trị chưa."
    elif [[ $r11 == *"alert protocol version"* ]]; then
        ok "TLS 1.1 bị từ chối ở biên (alert protocol_version), TLS 1.2 bắt tay được"
    else
        echo "  ! Không đo được TLS 1.1 — openssl máy này có thể không còn nói TLS 1.1. KHÔNG tính là đạt."
    fi
else
    echo "  ! Thiếu openssl — mục TLS CHƯA đo được."
fi

so_connector() {
    local mac_dinh=0
    [ "$CAU_HINH" = "$HOME/.cloudflared/config.yml" ] && mac_dinh=1
    ps -axo args= | awk -v c="$CAU_HINH" -v md="$mac_dinh" '
        $1 ~ /(^|\/)cloudflared$/ && / tunnel / && / run( |$)/ {
            cfg = ""
            for (i = 2; i <= NF; i++) {
                if ($i == "--config") cfg = $(i + 1)
                else if ($i ~ /^--config=/) cfg = substr($i, 10)
            }
            if (cfg == c || (cfg == "" && md)) n++
        }
        END { print n + 0 }'
}
if command -v cloudflared >/dev/null && [ -f "$CAU_HINH" ]; then
    n=$(so_connector)
    case "$n" in
        1) ok "đúng một tiến trình cloudflared đọc $CAU_HINH" ;;
        0) loi "không tiến trình cloudflared nào đọc $CAU_HINH trên máy này — mà mục 1 vừa thấy
     trang sống. Tunnel đang chạy bằng file khác hoặc ở máy khác: tìm nó trước khi tin mục 2b." ;;
        *) loi "$n tiến trình cloudflared cùng đọc $CAU_HINH — sửa ingress mà chỉ khởi động lại một
     cái là nửa số request đi qua luật cũ. Giữ lại dịch vụ launchd (dev.oj.cloudflared), tắt
     phần còn lại: ps -axo pid,args | grep 'cloudflared.*run'" ;;
    esac
else
    echo "  ! Bỏ qua mục 7 — không ở trên máy chạy tunnel (thiếu cloudflared hoặc $CAU_HINH)."
fi

echo
if [ "$hong" -eq 0 ]; then
    ok "Tunnel đạt. Nhắc lại: chạy lại script này MỖI LẦN sửa ingress."
    exit 0
fi
echo "✗ $hong ô hỏng. Đừng công bố tên miền cho tới khi chúng xanh." >&2
exit 1
