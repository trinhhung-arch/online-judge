import { goi, LoiApi } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DS, DUONG, VERDICT } from './duong-dan.js';
import { taoPhanTrang } from './phan-trang.js';

const o = khoiDong({ doiDangNhap: true });
if (o) {
    const form = document.getElementById('loc');
    const bang = document.getElementById('bang');
    const nutThem = document.getElementById('them');
    const chonVerdict = document.getElementById('verdict');
    const chonNgonNgu = document.getElementById('ngon-ngu');

    const tenNgonNgu = new Map();

    let deId;

    for (const [ma, nhan] of VERDICT) {
        const opt = chu('option', nhan);
        opt.value = ma;
        chonVerdict.append(opt);
    }

    function boLoc() {
        const q = new URLSearchParams();
        if (deId !== undefined) q.set('problemId', deId);
        if (chonVerdict.value) q.set('verdict', chonVerdict.value);
        if (chonNgonNgu.value) q.set('languageId', chonNgonNgu.value);
        return q;
    }

    function veDong(b) {
        const tr = chu('tr');

        tr.append(chu('td', gio(b.createdAt)));

        const oDe = chu('td');
        const link = chu('a', b.problemCode);
        link.href = `/problem.html?code=${encodeURIComponent(b.problemCode)}`;
        link.title = b.problemTitle || '';
        oDe.append(link);
        tr.append(oDe);

        tr.append(chu('td', tenNgonNgu.get(b.languageId) || `#${b.languageId}`));

        const oKq = chu('td');
        const nhan = b.verdict || (b.status === 'DONE' ? '—' : 'đang chấm');
        const lop = b.verdict ? `verdict ${b.verdict}` : 'verdict cho';
        const nut = chu('a', nhan, lop);
        nut.href = `/submission.html?id=${encodeURIComponent(b.submissionId)}`;
        oKq.append(nut);
        tr.append(oKq);

        tr.append(chu('td', b.score === null || b.score === undefined ? '—' : b.score));

        tr.append(chu('td', b.timeMs === null || b.timeMs === undefined ? '—' : `${b.timeMs} ms`));

        return tr;
    }

    const trang = taoPhanTrang({
        ds: DS.baiNop,
        boLoc,
        veDong,
        vao: bang,
        nutThem,
        o,
        khiTrong: 'Chưa có bài nộp nào khớp bộ lọc.',
        loiChung: 'Không tải được danh sách bài nộp.',
    });

    async function phanGiaiDe(ma) {
        if (!ma) return undefined;
        const de = await goi(DUONG.de.theoMa(ma));
        return de.problemId;
    }

    form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const ma = form.maDe.value.trim();
        try {
            deId = await phanGiaiDe(ma);
        } catch (e) {
            bao(o, e instanceof LoiApi && e.status === 404
                ? `Không có đề nào mang mã “${ma}”.`
                : (e instanceof LoiApi ? e.message : 'Không tra được mã đề.'), 'loi');
            return;
        }
        trang.lamMoi();
    });

    goi(DUONG.ngonNgu).then((ds) => {
        for (const n of ds) {
            tenNgonNgu.set(n.id, n.displayName);
            const opt = chu('option', n.displayName);
            opt.value = n.id;
            chonNgonNgu.append(opt);
        }
        if (bang.childElementCount) trang.lamMoi();
    }).catch(() => {
        chonNgonNgu.disabled = true;
        chonNgonNgu.insertAdjacentElement('afterend',
            chu('p', 'Không tải được danh sách ngôn ngữ; bộ lọc này tạm tắt.', 'goi-y'));
    });

    trang.lamMoi();
}
