/**
 * Trình soạn mã CodeMirror 6 — Bước 4.12.
 *
 * ★ NẠP TỪ CDN DẠNG ESM, KHÔNG BUILD STEP
 *
 * Quyết định của Bước 4.12: giao diện là trang tĩnh, không thêm Node vào CI. `+esm` của
 * jsDelivr trả về một bundle ES module dùng thẳng bằng `import`.
 *
 * ★ VÀ NÓ PHẢI CHẠY ĐƯỢC KHI CDN CHẾT
 *
 * Một trang nộp bài không nộp được vì CDN hỏng là một trang hỏng. Nếu import thất bại,
 * {@link gan} trả về một bộ điều khiển dựa trên `<textarea>` thường — mất tô màu cú pháp,
 * giữ nguyên khả năng nộp bài. Với một hệ thống mà điều không thể thoả hiệp thứ hai là
 * *không mất bài nộp*, đó là đánh đổi bắt buộc.
 */

const CDN = 'https://cdn.jsdelivr.net/npm';

const NGON_NGU = {
    cpp20: `${CDN}/@codemirror/lang-cpp@6.0.2/+esm`,
    py311: `${CDN}/@codemirror/lang-python@6.1.6/+esm`,
    java21: `${CDN}/@codemirror/lang-java@6.0.1/+esm`,
};

async function napCodeMirror() {
    const [cm, view] = await Promise.all([
        import(`${CDN}/codemirror@6.0.1/+esm`),
        import(`${CDN}/@codemirror/view@6.34.1/+esm`),
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
    } catch {
        // CDN chết. Hạ xuống textarea thường — xem javadoc của module.
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
