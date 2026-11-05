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
        return [];
    }
}

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
