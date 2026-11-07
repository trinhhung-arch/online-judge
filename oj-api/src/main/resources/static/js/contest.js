import { goi, LoiApi, phien } from './api.js';
import { chu, bao, vaiTroItNhat } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG, DS } from './duong-dan.js';
import { gan as ganBangXepHang } from './bang-xep-hang.js';

const o = khoiDong();
const slug = new URLSearchParams(location.search).get('slug');

const tieuDe = document.getElementById('tieu-de');
const tomTat = document.getElementById('tom-tat');
const bangDe = document.getElementById('bang-de');
const viSaoTrong = document.getElementById('vi-sao-trong');
const nutDangKy = document.getElementById('dang-ky');
const khuRaDe = document.getElementById('khu-ra-de');
const khuCongBo = document.getElementById('khu-cong-bo');
const dsMaDe = document.getElementById('ds-ma-de');
const soanDeRieng = document.getElementById('soan-de-rieng');

const NHAN_TRANG_THAI = {
    SAP_DIEN_RA: 'Sắp diễn ra',
    DANG_CHAY: 'Đang diễn ra',
    DA_KET_THUC: 'Đã kết thúc',
};

let kyThi = null;
let laNguoiRaDe = false;

function veDe(d) {
    const tr = chu('tr');
    tr.append(chu('td', d.label));

    const oMa = chu('td');
    const link = chu('a', d.code);
    link.href = `/problem.html?code=${encodeURIComponent(d.code)}`;
    oMa.append(link);
    tr.append(oMa);

    tr.append(chu('td', d.points));

    const oNguon = chu('td', d.soanRieng ? 'Riêng' : 'Mượn', 'chi-ra-de');
    oNguon.hidden = !laNguoiRaDe;
    tr.append(oNguon);

    const oSua = chu('td', null, 'chi-ra-de');
    oSua.hidden = !laNguoiRaDe;
    if (laNguoiRaDe) oSua.append(nutSua(d));
    tr.append(oSua);

    const oGo = chu('td', null, 'chi-ra-de');
    oGo.hidden = !laNguoiRaDe;
    if (laNguoiRaDe) oGo.append(nutGo(d));
    tr.append(oGo);

    return tr;
}

function nutGo(d) {
    if (kyThi.trangThai !== 'SAP_DIEN_RA') return chu('span', '—', 'goi-y');

    const nut = chu('button', 'Gỡ', 'phu');
    nut.type = 'button';
    nut.addEventListener('click', async () => {
        if (!confirm(`Gỡ đề ${d.code} (nhãn ${d.label}) khỏi kỳ thi? `
            + 'Đề không bị xoá — nó quay về kho đề chung.')) return;
        nut.disabled = true;
        bao(o, '');
        try {
            await goi(DUONG.kyThi.goDe(kyThi.id, d.problemId), { method: 'DELETE' });
            bao(o, `Đã gỡ đề ${d.code} khỏi kỳ thi.`, 'on');
            await tai();
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không gỡ được đề.', 'loi');
            nut.disabled = false;
        }
    });
    return nut;
}

function nutSua(d) {
    if (kyThi.trangThai !== 'SAP_DIEN_RA') {
        const p = chu('span', 'Khoá', 'goi-y');
        p.title = 'Kỳ thi đã bắt đầu — đề không sửa được nữa (FR-PROB-11).';
        return p;
    }
    const a = chu('a', 'Sửa');
    a.href = `/ra-de.html?id=${encodeURIComponent(d.problemId)}`;
    return a;
}

function veDanhSachDe() {
    bangDe.replaceChildren(...kyThi.cacDe.map(veDe));

    if (kyThi.cacDe.length) {
        viSaoTrong.hidden = true;
        return;
    }
    viSaoTrong.hidden = false;
    viSaoTrong.className = 'thong-bao';
    viSaoTrong.textContent = kyThi.trangThai === 'SAP_DIEN_RA'
        ? 'Đề bài được mở đúng giờ bắt đầu. Ngay cả số lượng đề cũng chưa được công bố.'
        : 'Kỳ thi này chưa có đề nào.';
}

function veDangKy() {
    if (kyThi.daDangKy) {
        nutDangKy.hidden = true;
        tomTat.append(chu('span', ' · Bạn đã đăng ký.'));
        return;
    }
    nutDangKy.hidden = !(phien() && kyThi.trangThai === 'SAP_DIEN_RA');
}

async function tai() {
    kyThi = await goi(DUONG.kyThi.theoSlug(slug));

    document.title = `${kyThi.title} · Online Judge`;
    tieuDe.textContent = kyThi.title;
    tomTat.replaceChildren(chu('span',
        `${kyThi.format} · ${NHAN_TRANG_THAI[kyThi.trangThai] || kyThi.trangThai}`
        + ` · ${gio(kyThi.startsAt)} → ${gio(kyThi.endsAt)}`));

    laNguoiRaDe = kyThi.duocSoan === true;
    for (const th of document.querySelectorAll('th.chi-ra-de')) th.hidden = !laNguoiRaDe;

    veDanhSachDe();
    veDangKy();

    khuRaDe.hidden = !laNguoiRaDe;
    soanDeRieng.href = `/ra-de.html?kyThi=${encodeURIComponent(kyThi.id)}`;
    khuCongBo.hidden = !vaiTroItNhat('ADMIN');
    if (!khuRaDe.hidden) goiYMaDe();

    ganBangXepHang(kyThi.id, { o });
}

async function goiYMaDe() {
    try {
        const trang = await goi(`${DS.de.url}?${DS.de.khoaSize}=100`);
        dsMaDe.replaceChildren();
        for (const de of trang.items) {
            const muc = chu('option');
            muc.value = de.code;
            muc.label = de.title;
            dsMaDe.append(muc);
        }
    } catch {
    }
}

async function doiMaDeRaId(maDe) {
    try {
        const de = await goi(DUONG.de.theoMa(maDe));
        return de.problemId;
    } catch (e) {
        if (e instanceof LoiApi && e.status === 404) {
            throw new LoiApi(404, 'contest.ma_de_khong_ro',
                `Không tìm thấy đề có mã “${maDe}”. `
                + 'Kiểm lại cột “Mã đề” ở trang Đề bài — và đề phải đã xuất bản.');
        }
        throw e;
    }
}

nutDangKy.addEventListener('click', async () => {
    nutDangKy.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.kyThi.dangKy(kyThi.id), { method: 'POST' });
        bao(o, 'Đã đăng ký. Hẹn gặp bạn lúc bắt đầu.', 'on');
        nutDangKy.hidden = true;
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không đăng ký được.', 'loi');
        nutDangKy.disabled = false;
    }
});

document.getElementById('form-them-de').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const form = ev.target;
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        const problemId = await doiMaDeRaId(form.elements.code.value.trim());
        await goi(DUONG.kyThi.themDe(kyThi.id), {
            method: 'POST',
            body: {
                problemId,
                label: form.label.value.trim(),
                points: Number(form.points.value),
            },
        });
        bao(o, 'Đã thêm đề.', 'on');
        await tai();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không thêm được đề.', 'loi');
    } finally {
        nut.disabled = false;
    }
});

document.getElementById('cong-bo').addEventListener('click', async () => {
    if (!confirm('Công bố bảng xếp hạng đầy đủ cho mọi người? Không hoàn tác được.')) {
        return;
    }
    bao(o, '');
    try {
        await goi(DUONG.kyThi.congBo(kyThi.id), { method: 'POST' });
        bao(o, 'Đã công bố bảng xếp hạng.', 'on');
        await tai();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không công bố được.', 'loi');
    }
});

if (!slug) {
    bao(o, 'Thiếu tham số kỳ thi trên đường dẫn.', 'loi');
} else {
    tai().catch((e) => {
        bao(o, e instanceof LoiApi ? e.message : 'Không tải được kỳ thi.', 'loi');
        tieuDe.textContent = 'Không mở được kỳ thi';
    });
}
