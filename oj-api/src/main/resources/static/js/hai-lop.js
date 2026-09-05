/**
 * Xác thực hai lớp trên trang hồ sơ — FR-AUTH-10 (V11).
 *
 * ★ MỌI CHUỖI TỪ SERVER VÀO DOM QUA `textContent`.
 * Không một `innerHTML` nào trong file này. Đây là trang hiển thị bí mật TOTP và mã dự
 * phòng; một lỗ XSS ở đúng đây đọc được cả hai. Xem javadoc `khung.js`.
 *
 * ★ MÃ QR DỰNG Ở TRÌNH DUYỆT, KHÔNG PHẢI Ở SERVER.
 * Sinh ảnh QR ở server nghĩa là bí mật đi thêm một vòng qua tầng ảnh, qua log truy cập, và
 * có thể qua cache của proxy. Ở đây nó chỉ đi từ response JSON vào bộ nhớ của tab.
 *
 * ★ TÁCH KHỎI `ho-so.js` vì hai lý do, và lý do thứ hai mới là lý do thật:
 *   1. `ho-so.js` đã 127 dòng, gộp vào là vượt trần 300 dòng (CLAUDE.md mục 7).
 *   2. Thư viện QR chỉ nạp cho trang này. Một module riêng làm ranh giới ấy nhìn thấy được
 *      — grep 'qrcode' ra đúng một file, thay vì phải đọc cả file hồ sơ để biết nó có đụng
 *      thư viện ngoài hay không.
 */

import { goi, LoiApi } from './api.js';
import { chu, bao } from './khung.js';
import { DUONG } from './duong-dan.js';

// KHÔNG gọi khoiDong() ở đây: `ho-so.js` nạp trước và đã gọi rồi. Gọi lần hai sẽ vẽ
// thanh điều hướng hai lần và gắn thêm một listener unhandledrejection nữa.

const o = document.getElementById('thong-bao');
const oTrangThai = document.getElementById('hai-lop-trang-thai');

const khoi = {
    tat:      document.getElementById('hai-lop-tat'),
    dangKy:   document.getElementById('hai-lop-dang-ky'),
    maDuPhong: document.getElementById('hai-lop-ma-du-phong'),
    bat:      document.getElementById('hai-lop-bat'),
};

/** Chỉ một khối hiện tại một thời điểm — trạng thái nhập nhằng là trạng thái người dùng bấm nhầm. */
function hien(ten, nhan) {
    Object.entries(khoi).forEach(([k, el]) => { el.hidden = k !== ten; });
    oTrangThai.textContent = nhan;
}

function veQr(uri) {
    const img = document.getElementById('hai-lop-qr');
    // Thư viện không nạp được (CDN chặn, mạng hỏng, SRI không khớp) thì ẩn ảnh đi và để
    // phần nhập tay làm việc. Một ô ảnh vỡ cạnh một khoá bí mật trông như trang bị lỗi.
    if (typeof window.qrcode !== 'function') {
        img.hidden = true;
        return;
    }
    const qr = window.qrcode(0, 'M');
    qr.addData(uri);
    qr.make();
    img.src = qr.createDataURL(5, 8);
    img.hidden = false;
}

async function taiTrangThai() {
    try {
        const tt = await goi(DUONG.toi.haiLop);
        if (tt.daBat) {
            hien('bat', 'Đang BẬT. Mỗi lần đăng nhập sẽ cần mã từ ứng dụng xác thực.');
        } else {
            hien('tat', 'Đang TẮT. Tài khoản chỉ được bảo vệ bằng mật khẩu.');
        }
    } catch (e) {
        oTrangThai.textContent = 'Không đọc được trạng thái xác thực hai lớp.';
    }
}

document.getElementById('nut-bat-hai-lop').addEventListener('click', async (ev) => {
    ev.target.disabled = true;
    bao(o, '');
    try {
        const banNhap = await goi(DUONG.toi.haiLopBatDau, { method: 'POST' });
        document.getElementById('hai-lop-bi-mat').textContent = banNhap.secretBase32;
        veQr(banNhap.otpauthUri);
        hien('dangKy', 'Chưa bật — hãy quét mã rồi nhập mã 6 chữ số để xác nhận.');
        document.getElementById('hai-lop-ma').focus();
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không bắt đầu được.', 'loi');
    } finally {
        ev.target.disabled = false;
    }
});

document.getElementById('form-hai-lop-xac-nhan').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const nut = ev.target.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        const kq = await goi(DUONG.toi.haiLopXacNhan, {
            method: 'POST',
            body: { ma: document.getElementById('hai-lop-ma').value.trim() },
        });
        const ds = document.getElementById('hai-lop-danh-sach-ma');
        ds.replaceChildren(...kq.maDuPhong.map((m) => {
            const li = document.createElement('li');
            li.appendChild(chu('code', m));
            return li;
        }));
        hien('maDuPhong', 'Đã BẬT. Hãy lưu mã dự phòng trước khi rời trang.');
        // Xoá khỏi DOM ngay: bí mật không còn việc gì để làm ở đây nữa.
        document.getElementById('hai-lop-bi-mat').textContent = '';
        document.getElementById('hai-lop-qr').removeAttribute('src');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không xác nhận được.', 'loi');
    } finally {
        nut.disabled = false;
        ev.target.reset();
    }
});

document.getElementById('nut-da-luu-ma').addEventListener('click', () => {
    // Xoá mã khỏi DOM khi người dùng nói đã lưu — chúng không cần nằm lại trong tab.
    document.getElementById('hai-lop-danh-sach-ma').replaceChildren();
    hien('bat', 'Đang BẬT. Mỗi lần đăng nhập sẽ cần mã từ ứng dụng xác thực.');
});

document.getElementById('form-hai-lop-tat').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const nut = ev.target.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.toi.haiLop, {
            method: 'DELETE',
            body: {
                password: document.getElementById('hai-lop-tat-mat-khau').value,
                ma: document.getElementById('hai-lop-tat-ma').value.trim(),
            },
        });
        bao(o, 'Đã tắt xác thực hai lớp.', 'on');
        hien('tat', 'Đang TẮT. Tài khoản chỉ được bảo vệ bằng mật khẩu.');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không tắt được.', 'loi');
    } finally {
        nut.disabled = false;
        ev.target.reset();
    }
});

taiTrangThai();
