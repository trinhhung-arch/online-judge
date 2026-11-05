function so(n, le) {
    if (n === null || n === undefined || Number.isNaN(n)) return '—';
    const [nguyen, thap] = Number(n).toFixed(le === undefined ? 0 : le).split('.');
    const co = nguyen.replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
    return thap ? `${co}.${thap}` : co;
}

export function dinhDang(v, donVi) {
    if (v === null || v === undefined) return '—';
    if (donVi === 'ti_le') return `${so(v * 100, 2)} %`;
    if (donVi === 'ms') return `${so(v)} ms`;
    if (donVi === 'bai_s') return `${so(v, 2)} bài/s`;
    if (donVi === 'mbps') return `${so(v, 1)} Mbps`;
    return so(v);
}

const cot = (s, n) => String(s).padEnd(n);
const cotPhai = (s, n) => String(s).padStart(n);

export function docSo(metrics, ten, thongKe) {
    const m = metrics[ten];
    if (!m || !m.values) return null;
    if (m.values.count === 0 || (m.values.passes === 0 && m.values.fails === 0)) return null;
    const v = m.values[thongKe];
    return v === undefined ? null : v;
}

function apDung(nhom, nguon, kichBan) {
    return nhom.filter((d) => d.nguon === nguon && d.kich_ban.includes(kichBan));
}

export function nguongK6(nguong, kichBan) {
    const ra = {};
    apDung(nguong.cung, 'k6', kichBan).forEach((d) => {
        ra[d.metric] = [`${d.thong_ke}<${d.toi_da === 0 ? 1 : d.toi_da}`];
    });
    return ra;
}

function ve(metrics, nguong, bc) {
    const d = [];
    const vach = '═'.repeat(78);
    d.push('', vach, `  ${bc.kichBan.toUpperCase()} · ${bc.moTa} · ${bc.base}`, vach, '');

    d.push('ĐƯỜNG API — đo ở client');
    apDung(nguong.cung, 'k6', bc.kichBan).forEach((r) => {
        const v = docSo(metrics, r.metric, r.thong_ke);
        const dau = v === null ? '⚠️  không đo được' : (v <= r.toi_da ? '✅' : '❌');
        d.push(`  ${cot(r.ma, 9)} ${cot(r.nhan, 34)} ${cotPhai(dinhDang(v, r.don_vi), 12)}`
            + `   < ${cot(dinhDang(r.toi_da, r.don_vi), 10)} ${dau}`);
    });

    d.push('', 'ĐƯỜNG CHẤM — CHỜ ĐO: bao-cao-db.py chạy sau khi hàng đợi rút cạn');
    nguong.cung.filter((d) => d.nguon !== 'k6' && d.kich_ban.includes(bc.kichBan)).forEach((r) => {
        d.push(`  ${cot(r.ma, 9)} ${cot(r.nhan, 34)} ${cotPhai('…', 12)}`
            + `   ${r.toi_da === 0 ? '= 0' : '< ' + dinhDang(r.toi_da, r.don_vi)}`);
    });

    d.push('', 'QUAN SÁT — đọc đường cong, không chấm');
    apDung(nguong.quan_sat, 'k6', bc.kichBan).forEach((r) => {
        const moc = r.moc === undefined ? '' : `   mốc ${dinhDang(r.moc, r.don_vi)}`;
        d.push(`  ${cot(r.ma, 9)} ${cot(r.nhan, 30)} `
            + cotPhai(dinhDang(docSo(metrics, r.metric, r.thong_ke), r.don_vi), 12) + moc);
    });

    d.push('');
    if (/localhost|127\.0\.0\.1|\[::1\]/.test(bc.base)) {
        d.push('  ⚠️  k6 chạy trên CHÍNH máy được đo: từ ~400 người ảo k6 ăn 1–2 core của máy chủ,');
        d.push('      nên số ở trên bi quan hơn sự thật. Số để nghiệm thu: chạy k6 từ máy khác qua LAN.', '');
    }
    return d.join('\n');
}

export function tomTat(data, nguong, bc) {
    const moTa = bc.kichBan === 'quet'
        ? `${bc.nguoi} người ảo · ${bc.thoiLuong} · dốc ${bc.docLen} (không tính vào p95)`
        : bc.moTa;
    const ra = { stdout: ve(data.metrics, nguong, { ...bc, moTa }) + '\n' };
    if (bc.raJson) {
        ra[bc.raJson] = JSON.stringify({
            kich_ban: bc.kichBan, mo_ta: moTa, base: bc.base, nguoi: bc.nguoi || null,
            metrics: data.metrics,
        }, null, 2);
    }
    return ra;
}
