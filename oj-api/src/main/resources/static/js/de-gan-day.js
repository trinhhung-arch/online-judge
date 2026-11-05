import { chu } from './khung.js';

const KHOA = 'oj.ra-de.gan-day';

const TOI_DA = 12;

function doc() {
    try {
        const ds = JSON.parse(localStorage.getItem(KHOA) || '[]');
        return Array.isArray(ds) ? ds : [];
    } catch {
        return [];
    }
}

export function nhoLai(problemId, code) {
    const ds = doc().filter((x) => x.id !== problemId);
    ds.unshift({ id: problemId, code });
    try {
        localStorage.setItem(KHOA, JSON.stringify(ds.slice(0, TOI_DA)));
    } catch {
    }
}

export function veGanDay(vao, moDe) {
    const ds = doc();
    vao.replaceChildren();
    if (!ds.length) return;

    vao.append(chu('p', 'Mở nhanh (nhớ trên máy này):', 'goi-y'));
    const hang = chu('p', null, 'hang');
    for (const x of ds) {
        const nut = chu('button', `#${x.id} · ${x.code || 'chưa đặt mã'}`, 'phu');
        nut.type = 'button';
        nut.addEventListener('click', () => moDe(x.id));
        hang.append(nut);
    }
    vao.append(hang);
}
