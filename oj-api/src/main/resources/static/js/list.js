import { goi, LoiApi, phien } from './api.js';
import { veThanh, bao, chu, vaiTroItNhat } from './khung.js';
import { DS } from './duong-dan.js';

veThanh();

const bang = document.getElementById('bang');
const o = document.getElementById('thong-bao');
const nutThem = document.getElementById('them');
let cursor = null;

const laNguoiRaDe = vaiTroItNhat('SETTER');
for (const th of document.querySelectorAll('th.chi-ra-de')) th.hidden = !laNguoiRaDe;

function thamSoLoc() {
    const form = document.getElementById('loc');
    const q = new URLSearchParams();
    if (form.tag.value.trim()) q.set('tag', form.tag.value.trim());
    if (form.daGiai.value) q.set('daGiai', form.daGiai.value);
    if (cursor) q.set('cursor', cursor);
    return q;
}

function veDong(de) {
    const tr = chu('tr');

    const ma = chu('td');
    const link = chu('a', de.code);
    link.href = `/problem.html?code=${encodeURIComponent(de.code)}`;
    ma.append(link);
    tr.append(ma);

    tr.append(chu('td', de.title));
    tr.append(chu('td', `${de.timeLimitMs} ms`));
    tr.append(chu('td', `${Math.round(de.memoryLimitKb / 1024)} MB`));

    const td = chu('td');
    const dau = chu('span', de.daGiai ? '✔' : '—', de.daGiai ? 'verdict AC' : '');
    dau.setAttribute('aria-label', de.daGiai ? 'Đã giải' : 'Chưa giải');
    td.append(dau);
    tr.append(td);

    const oSua = chu('td', null, 'chi-ra-de');
    oSua.hidden = !laNguoiRaDe;
    if (laNguoiRaDe) {
        const a = chu('a', 'Sửa');
        a.href = `/ra-de.html?ma=${encodeURIComponent(de.code)}`;
        oSua.append(a);
    }
    tr.append(oSua);

    return tr;
}

async function tai(noiTiep = false) {
    if (!noiTiep) {
        cursor = null;
        bang.replaceChildren();
    }
    bao(o, '');
    try {
        const trang = await goi(`${DS.de.url}?${thamSoLoc()}`);
        for (const de of trang.items) bang.append(veDong(de));
        cursor = trang.nextCursor;
        nutThem.hidden = !cursor;
        if (!bang.childElementCount) bao(o, 'Chưa có đề nào khớp bộ lọc.');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được danh sách đề.', 'loi');
    }
}

document.getElementById('loc').addEventListener('submit', (ev) => {
    ev.preventDefault();
    tai();
});
nutThem.addEventListener('click', () => tai(true));

if (!phien()) {
    const chon = document.getElementById('da-giai');
    chon.disabled = true;
    chon.insertAdjacentElement('afterend',
        chu('p', 'Đăng nhập để lọc theo bài đã giải.', 'goi-y'));
}

tai();
