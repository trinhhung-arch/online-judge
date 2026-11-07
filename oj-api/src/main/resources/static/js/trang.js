import { veThanh, bao, canDangNhap } from './khung.js';

export function khoiDong({ doiDangNhap = false } = {}) {
    veThanh();

    if (doiDangNhap && !canDangNhap()) {
        return null;
    }

    const o = document.getElementById('thong-bao');

    window.addEventListener('unhandledrejection', (ev) => {
        if (o) {
            bao(o, 'Trang gặp lỗi không mong muốn. Tải lại trang, hoặc thử lại sau ít phút.', 'loi');
        }
    });

    return o;
}

export function gio(isoUtc) {
    if (!isoUtc) return '—';
    const d = new Date(isoUtc);
    return Number.isNaN(d.getTime())
        ? '—'
        : d.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'medium' });
}
