/**
 * Đọc luồng SSE — Bước 4.12, FR-SUB-05.
 *
 * ★ VÌ SAO KHÔNG DÙNG `EventSource`
 *
 * `EventSource` không đặt được header, nên cách duy nhất để nó mang danh tính là nhét
 * access token vào query string. URL thì đi vào log của proxy, vào lịch sử trình duyệt,
 * và vào ảnh chụp màn hình mà người dùng gửi kèm khi báo lỗi — tức là token bị lộ qua ba
 * đường mà không ai cố ý mở.
 *
 * `fetch` + đọc stream tốn thêm khoảng bốn mươi dòng và giữ token trong header, đúng chỗ
 * của nó. Đổi lại còn được hai thứ: huỷ được bằng `AbortController`, và tự quyết định khi
 * nào ngừng thử lại thay vì để trình duyệt tự kết nối lại mãi.
 *
 * ★ FALLBACK REST LÀ BẮT BUỘC
 *
 * `oj-api/CLAUDE.md` mục 4: kết nối SẼ đứt — Cloudflare Tunnel giới hạn thời gian sống.
 * Nơi gọi phải truyền `onDut` và poll lại. Không có fallback = tính năng chưa xong.
 */

export function moLuong(url, token, { onSuKien, onDut }) {
    const dieuKhien = new AbortController();
    let dungHan = false;

    (async () => {
        try {
            const res = await fetch(url, {
                headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
                signal: dieuKhien.signal,
            });
            if (!res.ok || !res.body) {
                onDut?.('không mở được luồng');
                return;
            }

            const doc = res.body.getReader();
            const giaiMa = new TextDecoder();
            let dem = '';

            for (;;) {
                const { value, done } = await doc.read();
                if (done) break;
                dem += giaiMa.decode(value, { stream: true });

                // Một sự kiện SSE kết thúc bằng một dòng trống.
                let cat;
                while ((cat = dem.indexOf('\n\n')) >= 0) {
                    xuLy(dem.slice(0, cat), onSuKien);
                    dem = dem.slice(cat + 2);
                }
            }
        } catch (e) {
            if (dungHan) return;   // do chính ta huỷ, không phải sự cố
            onDut?.(String(e));
            return;
        }
        onDut?.('luồng đã đóng');
    })();

    return () => {
        dungHan = true;
        dieuKhien.abort();
    };
}

function xuLy(khoi, onSuKien) {
    let ten = 'message';
    const duLieu = [];
    for (const dong of khoi.split('\n')) {
        if (dong.startsWith(':')) continue;              // nhịp giữ kết nối
        if (dong.startsWith('event:')) ten = dong.slice(6).trim();
        else if (dong.startsWith('data:')) duLieu.push(dong.slice(5).trim());
    }
    if (!duLieu.length) return;
    try {
        onSuKien?.(ten, JSON.parse(duLieu.join('\n')));
    } catch {
        onSuKien?.(ten, null);
    }
}
