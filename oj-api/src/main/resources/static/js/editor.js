/**
 * Trình soạn mã CodeMirror 6 — Bước 4.12.
 *
 * ★ NẠP TỪ static/vendor/, KHÔNG TỪ CDN — từ 2026-09-24
 *
 * Quyết định của Bước 4.12: giao diện là trang tĩnh, không thêm Node vào CI. Bản đầu `import()`
 * thẳng bundle `+esm` của jsDelivr. Rà soát bảo mật 2026-09-24 (F3): bundle ấy do jsDelivr
 * DỰNG LÚC PHỤC VỤ nên không gắn được SRI, trong khi trang này giữ access token trong
 * localStorage — và CSP phải mở cả host cdn.jsdelivr.net. Giờ đúng các bundle ấy nằm ở
 * static/vendor/npm/<gói>@<bản>/esm.js, import bên trong đã trỏ về /vendor/, SHA-256 ghi ở
 * vendor/SHA256SUMS (TaiNguyenGiaoDienTest đối chiếu). Hành vi giữ nguyên từng byte — kể cả
 * việc cây import kéo nhiều bản @codemirror/state khác nhau (như trên CDN).
 *
 * ★ VÀ NÓ PHẢI CHẠY ĐƯỢC KHI MODULE KHÔNG NẠP ĐƯỢC
 *
 * Một trang nộp bài không nộp được vì CDN hỏng là một trang hỏng. Nếu import thất bại,
 * {@link gan} trả về một bộ điều khiển dựa trên `<textarea>` thường — mất tô màu cú pháp,
 * giữ nguyên khả năng nộp bài. Với một hệ thống mà điều không thể thoả hiệp thứ hai là
 * *không mất bài nộp*, đó là đánh đổi bắt buộc.
 */

const CDN = '/vendor/npm';

const NGON_NGU = {
    cpp20: `${CDN}/@codemirror/lang-cpp@6.0.2/esm.js`,
    py311: `${CDN}/@codemirror/lang-python@6.1.6/esm.js`,
    java21: `${CDN}/@codemirror/lang-java@6.0.1/esm.js`,
};

async function napCodeMirror() {
    const [cm, view] = await Promise.all([
        import(`${CDN}/codemirror@6.0.1/esm.js`),
        import(`${CDN}/@codemirror/view@6.34.1/esm.js`),
    ]);
    return { cm, view };
}

async function napNgonNgu(code) {
    const url = NGON_NGU[code];
    if (!url) return [];
    try {
        const m = await import(url);
        const tao = m.cpp || m.python || m.java;
        return tao ? [tao()] : [];
    } catch {
        return [];   // không tô màu được thì vẫn gõ được
    }
}

/**
 * @returns {{doc: () => string, dat: (s: string) => void, doiNgonNgu: (c: string) => void}}
 */
export async function gan(khung, textarea, { onThayDoi, ngonNguBanDau }) {
    try {
        const { cm, view } = await napCodeMirror();
        const ngonNgu = new cm.Compartment();
        const capNhat = view.EditorView.updateListener.of((v) => {
            if (v.docChanged) onThayDoi(v.state.doc.toString());
        });

        const editor = new cm.EditorView({
            doc: '',
            extensions: [
                cm.basicSetup,
                ngonNgu.of(await napNgonNgu(ngonNguBanDau)),
                capNhat,
                view.EditorView.lineWrapping,
            ],
            parent: khung,
        });

        return {
            doc: () => editor.state.doc.toString(),
            dat: (s) => editor.dispatch({
                changes: { from: 0, to: editor.state.doc.length, insert: s },
            }),
            doiNgonNgu: async (c) => editor.dispatch({
                effects: ngonNgu.reconfigure(await napNgonNgu(c)),
            }),
        };
    } catch (e) {
        // Không dựng được CodeMirror. Hạ xuống textarea thường — xem javadoc của module.
        //
        // ★ GHI RA CONSOLE, đừng nuốt im. Đo 2026-09-24: nhánh này chạy ở MỌI lượt mở trang —
        // cả trên CDN gốc lẫn bản vendor — vì cây import kéo nhiều bản @codemirror/state
        // ("Unrecognized extension value… multiple instances of @codemirror/state"). Nộp bài
        // vẫn chạy (đúng thiết kế), nhưng không ai biết tô màu đã chết từ bao giờ, vì
        // `catch {}` không để lại dấu vết nào. Sửa tận gốc cần một bundle MỘT bản state.
        console.warn('Trình soạn mã: không dựng được CodeMirror, dùng textarea thường —', e);
        textarea.hidden = false;
        textarea.removeAttribute('aria-hidden');
        textarea.rows = 20;
        textarea.addEventListener('input', () => onThayDoi(textarea.value));
        khung.append(textarea);
        return {
            doc: () => textarea.value,
            dat: (s) => { textarea.value = s; },
            doiNgonNgu: () => {},
        };
    }
}
