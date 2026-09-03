/** Đăng nhập và đăng ký — FR-AUTH-01, 02. Bước 4.12. */

import { goi, luuPhien, LoiApi } from './api.js';
import { veThanh, bao, thamSo } from './khung.js';
import { DUONG } from './duong-dan.js';

veThanh();

const o = document.getElementById('thong-bao');

/**
 * Vì sao người dùng đang đứng ở đây.
 *
 * Bị đưa về trang đăng nhập mà không có lời giải thích là trải nghiệm khó chịu nhất của
 * một hệ thống có phiên: người ta tưởng mình bấm nhầm, hoặc tưởng hệ thống hỏng. Một câu
 * là đủ, và nó phải là câu ĐÚNG — nên lý do đi qua URL từ nơi đã biết, chứ không đoán.
 */
const LY_DO = {
    'doi-mat-khau': ['Đã đổi mật khẩu. Đăng nhập lại bằng mật khẩu mới.', 'on'],
};
const viSao = LY_DO[thamSo('vi-sao')];
if (viSao) bao(o, viSao[0], viSao[1]);

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
    gui(ev.target, DUONG.auth.dangNhap, (phien) => {
        luuPhien(phien);
        location.href = tiepTuc();
    });
});

document.getElementById('form-dang-ky').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const form = ev.target;
    gui(form, DUONG.auth.dangKy, () => {
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
