/**
 * Thanh điều hướng và tiện ích chung — Bước 4.12.
 *
 * ★ `chu()` là hàm quan trọng nhất file này.
 *
 * Mọi chuỗi đến từ server đi vào DOM qua `textContent`, không qua `innerHTML`. Tên đề, tên
 * người dùng, thông báo lỗi — tất cả đều là dữ liệu người khác nhập. Một chỗ dùng `innerHTML`
 * là một lỗ XSS, và trang này giữ access token trong localStorage.
 *
 * Ngoại lệ duy nhất: `statementHtml`, đã được server render và escape bằng
 * `CommonMarkStatementRenderer.escapeHtml(true)`. Nó được đánh dấu rõ ở chỗ dùng.
 *
 * Link "bỏ qua tới nội dung chính" KHÔNG dựng ở đây mà nằm trong HTML tĩnh của từng trang —
 * thứ giúp người dùng bàn phím không được phụ thuộc vào việc module này nạp thành công.
 */

import { phien, xoaPhien, goi } from './api.js';

/** Tạo một phần tử với văn bản AN TOÀN. */
export function chu(the, vanBan, lop) {
    const el = document.createElement(the);
    if (vanBan !== undefined && vanBan !== null) el.textContent = String(vanBan);
    if (lop) el.className = lop;
    return el;
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
    const de = chu('a', 'Đề bài');
    de.href = '/';
    nav.append(de);
    thanh.append(nav);

    const khuPhien = chu('div', null, 'phien');
    if (p) {
        khuPhien.append(chu('span', p.handle));
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
            await goi('/api/v1/auth/logout', { method: 'POST', body: { refreshToken: p.refreshToken } });
        }
    } catch {
        // Đăng xuất là idempotent ở server, và frontend xoá token dù server trả gì.
        // Giữ người dùng lại trong một phiên họ vừa nói là muốn rời khỏi là hành vi sai.
    }
    xoaPhien();
    location.href = '/';
}

/**
 * Hiện một thông báo cho người đọc màn hình VÀ cho người nhìn.
 *
 * Vùng thông báo mang `aria-live="polite"` trong HTML, nên chỉ cần đổi `textContent` là
 * trình đọc màn hình đọc lên. Đây là điều kiện để a11y mức A có nghĩa trên một trang mà
 * kết quả đến bất đồng bộ.
 */
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
