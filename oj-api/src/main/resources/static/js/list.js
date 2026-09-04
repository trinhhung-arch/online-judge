/** Danh sách đề — FR-PROB-09. Bước 4.12. */

import { goi, LoiApi, phien } from './api.js';
import { veThanh, bao, chu, vaiTroItNhat } from './khung.js';
import { DS } from './duong-dan.js';

veThanh();

const bang = document.getElementById('bang');
const o = document.getElementById('thong-bao');
const nutThem = document.getElementById('them');
let cursor = null;

/**
 * Người xem có phải người ra đề không — quyết định một lần, dùng cho mọi dòng.
 *
 * KHÔNG phải chốt bảo mật: `AuthorProblemUseCase` mới là nơi chặn (bất biến #11). Ẩn nút
 * chỉ để người không có quyền khỏi bấm vào một trang sẽ từ chối họ.
 */
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

    // ★ Không dùng riêng màu để truyền tin (a11y mức A): ký hiệu kèm nhãn cho trình đọc.
    const td = chu('td');
    const dau = chu('span', de.daGiai ? '✔' : '—', de.daGiai ? 'verdict AC' : '');
    dau.setAttribute('aria-label', de.daGiai ? 'Đã giải' : 'Chưa giải');
    td.append(dau);
    tr.append(td);

    // ★ Sửa đề đi qua MÃ đề, không qua id.
    //
    // ProblemSummaryResponse cố ý không mang problemId, và không nên mang: id là định danh
    // nội bộ, còn cả giao diện này nói bằng mã. `ra-de.html?ma=<mã>` tự đổi mã ra id bằng
    // đúng endpoint công khai — mọi đề trong danh sách này đều đã xuất bản, nên đường ấy
    // luôn tra được.
    //
    // Tên tham số nói rõ nó là mã, nên không có gì phải đoán: một đề mang mã "15" vẫn mở
    // đúng, thay vì bị hiểu thành đề số 15.
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

// Bộ lọc "đã giải" cần đăng nhập; server trả 400 với câu giải thích, nhưng nói trước thì
// người dùng không phải thử mới biết.
if (!phien()) {
    const chon = document.getElementById('da-giai');
    chon.disabled = true;
    chon.insertAdjacentElement('afterend',
        chu('p', 'Đăng nhập để lọc theo bài đã giải.', 'goi-y'));
}

tai();
