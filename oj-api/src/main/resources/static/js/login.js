/** Đăng nhập và đăng ký — FR-AUTH-01, 02. Bước 4.12. */

import { goi, luuPhien, LoiApi } from './api.js';
import { veThanh, bao, thamSo } from './khung.js';

veThanh();

const o = document.getElementById('thong-bao');

function tiepTuc() {
    // Chỉ nhận đường dẫn nội bộ. Một tham số `tiep=https://ke-tan-cong.test` là open
    // redirect — người dùng bấm link của ta, đăng nhập thật, rồi bị đẩy sang trang giả.
    const t = thamSo('tiep');
    return t && t.startsWith('/') && !t.startsWith('//') ? t : '/';
}

async function gui(form, duongDan, sauKhiXong) {
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        const than = Object.fromEntries(new FormData(form).entries());
        const kq = await goi(duongDan, { method: 'POST', body: than });
        sauKhiXong(kq);
    } catch (e) {
        // Câu chữ đến thẳng từ server. GlobalExceptionHandler đã bảo đảm nó an toàn và
        // bằng tiếng Việt — dịch lại ở đây là tạo bản dịch thứ hai sẽ lạc hậu trước.
        bao(o, e instanceof LoiApi ? e.message : 'Không kết nối được máy chủ.', 'loi');
        nut.disabled = false;
    }
}

document.getElementById('form-dang-nhap').addEventListener('submit', (ev) => {
    ev.preventDefault();
    gui(ev.target, '/api/v1/auth/login', (phien) => {
        luuPhien(phien);
        location.href = tiepTuc();
    });
});

document.getElementById('form-dang-ky').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const form = ev.target;
    gui(form, '/api/v1/auth/register', () => {
        // Server cố ý KHÔNG tự đăng nhập sau khi đăng ký (xem AuthController): hai hành
        // động sẽ tách ra khi có xác minh email ở v1.1. Đăng nhập hộ ở đây bằng chính
        // thông tin vừa nhập giữ trải nghiệm liền mạch mà không cần server đổi gì.
        const dn = document.getElementById('form-dang-nhap');
        dn.dinhDanh.value = form.handle.value;
        dn.password.value = form.password.value;
        bao(o, 'Đã tạo tài khoản. Đang đăng nhập…', 'on');
        dn.requestSubmit();
    });
});
