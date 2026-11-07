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

                let cat;
                while ((cat = dem.indexOf('\n\n')) >= 0) {
                    xuLy(dem.slice(0, cat), onSuKien);
                    dem = dem.slice(cat + 2);
                }
            }
        } catch (e) {
            if (dungHan) return;
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
        if (dong.startsWith(':')) continue;
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
