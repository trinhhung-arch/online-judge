import { goi, LoiApi } from './api.js';
import { chu, bao, vaiTroItNhat } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DS, DUONG, TRANG_THAI_KY_THI } from './duong-dan.js';
import { taoPhanTrang } from './phan-trang.js';

const o = khoiDong();
const bang = document.getElementById('bang');
const nutThem = document.getElementById('them');

function veDong(k) {
    const tr = chu('tr');

    const oTen = chu('td');
    const link = chu('a', k.title);
    link.href = `/contest.html?slug=${encodeURIComponent(k.slug)}`;
    oTen.append(link);
    if (k.registrationRequired) {
        oTen.append(chu('p', 'Cần đăng ký trước', 'goi-y'));
    }
    tr.append(oTen);

    tr.append(chu('td', k.format));
    tr.append(chu('td', gio(k.startsAt)));
    tr.append(chu('td', gio(k.endsAt)));

    const [nhan, lop] = TRANG_THAI_KY_THI[k.trangThai] || [k.trangThai, ''];
    tr.append(chu('td', nhan, lop ? `verdict ${lop}` : ''));

    return tr;
}

const trang = taoPhanTrang({
    ds: DS.kyThi,
    boLoc: () => new URLSearchParams(),
    veDong,
    vao: bang,
    nutThem,
    o,
    khiTrong: 'Chưa có kỳ thi nào.',
    loiChung: 'Không tải được lịch thi.',
});

function sangUtc(giaTriLocal) {
    if (!giaTriLocal) return null;
    const d = new Date(giaTriLocal);
    return Number.isNaN(d.getTime()) ? null : d.toISOString();
}

function gioDiaPhuong(d) {
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

function rangBuocGio() {
    const oBatDau = document.getElementById('bat-dau');
    const oKetThuc = document.getElementById('ket-thuc');
    const oDongBang = document.getElementById('dong-bang');
    if (!oBatDau) return;

    oBatDau.min = gioDiaPhuong(new Date(Date.now() + 60000));

    const dongBo = () => {
        oKetThuc.min = oBatDau.value || oBatDau.min;
        oDongBang.min = oBatDau.value || oBatDau.min;
        if (oKetThuc.value) oDongBang.max = oKetThuc.value;
    };
    oBatDau.addEventListener('change', dongBo);
    oKetThuc.addEventListener('change', dongBo);
    [oBatDau, oKetThuc, oDongBang].forEach((el) =>
        el.addEventListener('input', () => el.removeAttribute('aria-invalid')));
    dongBo();
}

if (vaiTroItNhat('SETTER')) {
    document.getElementById('khu-tao').hidden = false;
    rangBuocGio();
}

document.getElementById('form-tao').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = ev.target;
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.kyThi.tao, {
            method: 'POST',
            body: {
                slug: form.slug.value.trim(),
                title: form.title.value.trim(),
                format: form.format.value,
                startsAt: sangUtc(form.startsAt.value),
                endsAt: sangUtc(form.endsAt.value),
                freezeAt: sangUtc(form.freezeAt.value),
                revealAfterEnd: form.revealAfterEnd.checked,
            },
        });
        location.href = `/contest.html?slug=${encodeURIComponent(form.slug.value.trim())}`;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tạo được kỳ thi.', 'loi');
        const oLoi = { 'contest.bat_dau_trong_qua_khu': 'bat-dau',
                       'contest.khung_gio_khong_hop_le': 'ket-thuc',
                       'contest.gio_dong_bang_khong_hop_le': 'dong-bang' }[e?.code];
        if (oLoi) {
            const el = document.getElementById(oLoi);
            el.setAttribute('aria-invalid', 'true');
            el.focus();
            el.scrollIntoView({ block: 'center' });
        }
        nut.disabled = false;
    }
});

trang.lamMoi();
