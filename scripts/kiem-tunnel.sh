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
#     404 = ĐẠT.  Cloudflare không biết đường dẫn ấy. Request chưa từng tới máy anh.
#     401 = HỎNG. Request ĐÃ tới InternalSecretFilter và bị nó chặn — nghĩa là lớp mạng
#           thủng, chỉ còn shared secret đứng giữa người lạ và quyền GHI VERDICT cho bất
#           kỳ bài nộp nào. Ai đoán được secret là đoạt được cả hệ thống.
#     200 = thảm hoạ.
#
# Đây là chỗ dễ đọc ngược nhất trong cả bộ script: 401 trông như "đã bị chặn, tốt", trong
# khi nó là dòng báo động duy nhất.
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
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 "https://$MIEN$1" 2>/dev/null
}

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
         /internal/judge/benchmark /internal/judge/testdata; do
    m=$(ma "$p")
    case "$m" in
        404) ok "$p → 404 (Cloudflare không biết đường dẫn này)" ;;
        401|403) loi "$p → $m — LỚP MẠNG THỦNG.
     $m nghĩa là request ĐÃ tới InternalSecretFilter. Cửa đang mở ra internet và thứ duy
     nhất còn lại là shared secret. Sửa ingress trong ~/.cloudflared/config.yml: luật
     'path: ^/internal(/|\$)' phải nằm TRƯỚC luật bắt-tất-cả." ;;
        000) loi "$p → không kết nối được. KHÔNG phải 'đã chặn' — là chưa đo được." ;;
        *) loi "$p → $m — không phải 404. Đọc ingress." ;;
    esac
done

# ---- 3. Actuator ------------------------------------------------------------
for p in /actuator/health /actuator/env /actuator/configprops; do
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

# ---- 5. HTTP phải chuyển sang HTTPS ------------------------------------------
m=$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "http://$MIEN/" 2>/dev/null || echo 000)
case "$m" in
    301|302|307|308) ok "http:// → chuyển hướng $m sang https" ;;
    000) echo "  ! Không gọi được http:// — nhiều khả năng đúng, Cloudflare đã chặn sẵn." ;;
    *) loi "http:// trả $m mà không chuyển hướng. Bật 'Always Use HTTPS' trong Cloudflare." ;;
esac

echo
if [ "$hong" -eq 0 ]; then
    ok "Tunnel đạt. Nhắc lại: chạy lại script này MỖI LẦN sửa ingress."
    exit 0
fi
echo "✗ $hong ô hỏng. Đừng công bố tên miền cho tới khi chúng xanh." >&2
exit 1
