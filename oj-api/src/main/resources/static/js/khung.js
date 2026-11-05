import { phien, xoaPhien, goi } from './api.js';
import { DUONG } from './duong-dan.js';

export function chu(the, vanBan, lop) {
    const el = document.createElement(the);
    if (vanBan !== undefined && vanBan !== null) el.textContent = String(vanBan);
    if (lop) el.className = lop;
    return el;
}

const THU_BAC = ['USER', 'SETTER', 'ADMIN'];

export function vaiTroItNhat(canCo) {
    const dangCo = phien()?.role;
    if (!dangCo) return false;
    const a = THU_BAC.indexOf(dangCo);
    const b = THU_BAC.indexOf(canCo);
    return a >= 0 && b >= 0 && a >= b;
}

export function veThanh() {
    const p = phien();
    const thanh = chu('header');
    thanh.className = 'thanh';

    const hieu = chu('a', 'Online Judge', 'hieu');
    hieu.href = '/';
    thanh.append(hieu);

    const nav = chu('nav');
    nav.setAttribute('aria-label', 'Điều hướng chính');
    const muc = [['/', 'Đề bài'], ['/contests.html', 'Kỳ thi']];
    if (p) muc.push(['/bai-nop.html', 'Bài nộp của tôi']);
    if (vaiTroItNhat('SETTER')) muc.push(['/ra-de.html', 'Soạn đề']);
    if (vaiTroItNhat('ADMIN')) {
        muc.push(['/quan-tri.html', 'Vận hành']);
        muc.push(['/nhat-ky.html', 'Nhật ký']);
    }
    muc.push(['/trang-thai.html', 'Trạng thái']);

    for (const [href, nhan] of muc) {
        const a = chu('a', nhan);
        a.href = href;
        if (location.pathname === href) a.setAttribute('aria-current', 'page');
        nav.append(a);
    }
    thanh.append(nav);

    const khuPhien = chu('div', null, 'phien');
    if (p) {
        const hoSo = chu('a', p.handle);
        hoSo.href = '/ho-so.html';
        khuPhien.append(hoSo);
        const ra = chu('button', 'Đăng xuất', 'phu');
        ra.addEventListener('click', dangXuat);
        khuPhien.append(ra);
    } else {
        const vao = chu('a', 'Đăng nhập', 'nut');
        vao.href = '/login.html';
        khuPhien.append(vao);
    }
    thanh.append(khuPhien);

    document.body.prepend(thanh);
}

async function dangXuat() {
    const p = phien();
    try {
        if (p?.refreshToken) {
            await goi(DUONG.auth.dangXuat, { method: 'POST', body: { refreshToken: p.refreshToken } });
        }
    } catch {
    }
    xoaPhien();
    location.href = '/';
}

export function bao(o, thongDiep, loai = '') {
    o.textContent = thongDiep || '';
    o.className = `thong-bao ${loai}`;
}

export function canDangNhap() {
    if (phien()) return true;
    location.href = `/login.html?tiep=${encodeURIComponent(location.pathname + location.search)}`;
    return false;
}

export function thamSo(ten) {
    return new URLSearchParams(location.search).get(ten);
}
