#!/usr/bin/env bash
# =============================================================================
# Kiểm tunnel TỪ NGOÀI vào — chạy sau khi dựng, và chạy lại mỗi lần sửa config.
#
#   ./scripts/kiem-tunnel.sh oj.vi-du.com
#
# ★ VÌ SAO PHẢI CÓ SCRIPT NÀY, KHÔNG PHẢI "ĐỌC LẠI CONFIG LÀ ĐỦ"
# Một cấu hình tunnel sai KHÔNG báo lỗi: tunnel vẫn lên, trang chủ vẫn chạy, giao diện vẫn
# đăng nhập được. Thứ duy nhất khác là có thêm một cánh cửa mở. Đọc lại YAML không phát
# hiện được điều đó — chỉ có gọi thật từ ngoài mới phát hiện được.
#
# ★ Ô QUAN TRỌNG NHẤT LÀ /internal/judge/claim, VÀ CÁCH ĐỌC NÓ RẤT PHẢN TRỰC GIÁC
#   /internal có BA lớp: (1) luật ingress của cloudflared, (2) oj-api từ chối mọi request
#   mang header Cloudflare (InternalSecretFilter, từ 2026-09-23), (3) shared secret.
#     404 = ĐẠT.  Lớp 1 hoặc lớp 2 đã chặn. Nhìn từ ngoài KHÔNG phân biệt được lớp nào —
#           cố ý, để người dò không biết đường dẫn có thật — nên mục 2b đo riêng lớp 1.
#     401 = HỎNG. Request ĐÃ tới bước kiểm secret — cả lớp 1 lẫn lớp 2 cùng thủng, chỉ còn
#           shared secret đứng giữa người lạ và quyền GHI VERDICT cho bất kỳ bài nộp nào.
#     200 = thảm hoạ.
#
# Đây là chỗ dễ đọc ngược nhất trong cả bộ script: 401 trông như "đã bị chặn, tốt", trong
# khi nó là dòng báo động duy nhất.
#
# ★ PHẢI THỬ CẢ BIẾN THỂ ĐƯỜNG DẪN, và phải gửi NGUYÊN VĂN (curl --path-as-is).
#   Luật cũ `^/internal(/|$)` bị qua mặt bằng `//internal/…`, `/./internal/…`,
#   `/internal;x=1/…` — Tomcat chuẩn hoá chúng về /internal/judge/*, cloudflared thì không.
#   Bản script cũ chỉ thử đường dẫn chuẩn nên báo ✓ trên đúng cấu hình thủng ấy (2026-09-23).
#   Không có --path-as-is thì curl tự gộp `/./` trước khi gửi và phép thử mất nghĩa.
# =============================================================================
set -uo pipefail

MIEN=${1:-}
[ -n "$MIEN" ] || { echo "Dùng: $0 <tên-miền>   ví dụ: $0 oj.vi-du.com" >&2; exit 2; }
MIEN=${MIEN#https://}; MIEN=${MIEN#http://}; MIEN=${MIEN%%/*}

loi() { echo "✗ $*" >&2; hong=$((hong + 1)); }
ok()  { echo "✓ $*"; }
hong=0

# curl TỰ IN 000 khi không kết nối được, rồi thoát khác 0. Thêm `|| echo 000` vào đây
# sẽ nối thêm một chuỗi nữa và ra "000000" — đo thật lúc chạy thử 2026-09-05.
ma() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 --path-as-is "https://$MIEN$1" 2>/dev/null
}

# Cùng bộ với oj-api/src/test/java/dev/oj/it/InternalQuaTunnelHttpIT.java — sửa thì sửa cả hai.
BIEN_THE_INTERNAL="//internal/judge/claim /./internal/judge/claim
/api/v1/../../internal/judge/claim /internal;x=1/judge/claim /%2e/internal/judge/claim
/api/v1/%2e%2e/%2e%2e/internal/judge/claim"
BIEN_THE_ACTUATOR="//actuator/health /./actuator/health /actuator;x=1/health"

echo "── Kiểm tunnel https://$MIEN ──"
echo

# ---- 1. Bề mặt công khai phải SỐNG ------------------------------------------
# Kiểm cái này trước: nếu nó hỏng thì mọi ô 404 bên dưới là 404 vì tunnel chết,
# không phải vì luật chặn hoạt động. Một bảng toàn ✓ trên một tunnel đã chết là
# đúng kiểu báo cáo mà cả dự án này đang cố loại bỏ.
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

# ---- 2. Cửa worker phải KHÔNG TỒN TẠI với internet ---------------------------
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

# ---- 2b. Lớp 1 ĐO RIÊNG: luật ingress, offline -------------------------------
# Mục 2 thấy 404 cả khi chỉ lớp 2 còn chặn — lớp 1 hỏng thì không ai hay, cho tới ngày lớp 2
# cũng hỏng. `cloudflared tunnel ingress rule` chạy chính bộ so khớp của cloudflared trên file
# cấu hình, không gửi gì ra mạng, nên nói được luật nào khớp cho từng đường dẫn.
CAU_HINH=${CLOUDFLARED_CONFIG:-$HOME/.cloudflared/config.yml}
if command -v cloudflared >/dev/null && [ -f "$CAU_HINH" ]; then
    # Trước hết: file này có PHỤC VỤ $MIEN không. Không thì mọi đường dẫn rơi vào luật bắt-tất-cả
    # 404 và cả mục xanh mà không đo gì — đã xảy ra: tunnel-do.sh gọi script này cho tên miền đo
    # trong khi CAU_HINH mặc định là config PROD (phát hiện 2026-09-23).
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

# ---- 3. Actuator ------------------------------------------------------------
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

# ---- 4. Header bảo mật có sống sót qua Cloudflare không ----------------------
# Cloudflare có thể thêm/sửa header. CSP do chính oj-api đặt, nên nếu nó biến mất thì
# hoặc filter không chạy, hoặc có một lớp nào đó ở giữa đang gỡ nó ra.
h=$(curl -sI --max-time 10 "https://$MIEN/" 2>/dev/null)
echo "$h" | grep -qi '^content-security-policy:' \
    && ok "Content-Security-Policy sống sót qua Cloudflare" \
    || loi "Không thấy Content-Security-Policy. SecurityHeadersFilter không chạy, hoặc bị gỡ ở giữa."
echo "$h" | grep -qi '^strict-transport-security:' \
    && ok "HSTS đã bật" \
    || echo "  ! HSTS chưa bật — đặt OJ_HSTS_MAX_AGE=31536000 rồi khởi động lại oj-api.
    Chỉ bật khi CHẮC CHẮN tên miền này sẽ luôn có HTTPS: trình duyệt nhớ rất lâu."
# Response /api/v1 là dữ liệu nhìn bằng vai trò người gọi — không trình duyệt hay edge nào được
# giữ lại (KhongLuuDemApiFilter). GET chứ không HEAD: đo đúng thứ trình duyệt nhận.
h=$(curl -s -D - -o /dev/null --max-time 10 "https://$MIEN/api/v1/status" 2>/dev/null)
echo "$h" | grep -qi '^cache-control: *no-store' \
    && ok "/api/v1 mang Cache-Control: no-store qua Cloudflare" \
    || loi "/api/v1/status thiếu 'Cache-Control: no-store' (thấy: $(echo "$h" | grep -i '^cache-control:' | tr -d '\r' || echo 'không có header ấy')).
     oj-api chưa chạy bản có KhongLuuDemApiFilter, hoặc Cloudflare đang ghi đè header (Browser Cache TTL / Cache Rule)."

# ---- 5. HTTP phải chuyển sang HTTPS ------------------------------------------
m=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://$MIEN/" 2>/dev/null || echo 000)
case "$m" in
    301|302|307|308) ok "http:// → chuyển hướng $m sang https" ;;
    000) echo "  ! Không gọi được http:// — nhiều khả năng đúng, Cloudflare đã chặn sẵn." ;;
    *) loi "http:// trả $m mà không chuyển hướng. Bật 'Always Use HTTPS' trong Cloudflare." ;;
esac

# ---- 6. TLS tối thiểu ở biên -------------------------------------------------
# Minimum TLS là một ô chọn trên dashboard, không nằm trong file nào của repo. Lần đổi đầu
# (2026-09-23) ô KHÔNG được lưu — vẫn "TLS 1.0 (default)" — và không gì báo cả.
# openssl chứ không curl: curl hệ thống của macOS không nói được TLS 1.3, và không phân biệt
# "server từ chối" với "client không hỗ trợ". BA trạng thái, không phải hai: server gửi alert
# protocol_version = ĐẠT · bắt tay được = HỎNG · còn lại = CHƯA ĐO (OpenSSL 3 tắt TLS 1.1 ở phía
# client) — và chưa đo thì không được in ✓.
# ★ Đọc dòng "Cipher is", KHÔNG đọc "Protocol :". LibreSSL in "Protocol : TLSv1.1" trong mục
#   SSL-Session cả khi bắt tay HỎNG — đo 2026-09-23, bản đầu của mục này báo đỏ trên một biên
#   đã chặn TLS 1.1. Bắt tay xong thì có tên cipher thật; hỏng thì "Cipher is (NONE)".
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

# ---- 7. Đúng MỘT connector đọc cấu hình này ----------------------------------
# Hai tiến trình `cloudflared tunnel run` cùng tunnel là hai bộ luật có thể lệch nhau: sửa
# ingress rồi khởi động lại một cái, Cloudflare vẫn chia request cho cả hai — nửa đi qua luật
# cũ. Đã xảy ra đến 2026-09-23; từ đó tunnel chạy bằng infra/launchd/dev.oj.cloudflared.plist.
# Đếm tiến trình ĐỌC $CAU_HINH: `--config` trỏ vào nó, hoặc không có `--config` khi nó là file
# mặc định. Tunnel đo (tunnel-do.sh) có --config riêng nên không bị đếm nhầm.
# Chỉ thấy MÁY NÀY. Connector ở máy khác dùng cùng credentials thì phải `cloudflared tunnel info`,
# mà lệnh đó cần cert.pem — thứ cố ý không nằm trên máy.
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
