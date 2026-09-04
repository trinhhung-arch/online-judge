/**
 * Đăng nhập và đăng ký — FR-AUTH-01, 02. Bước 4.12.
 *
 * ★ KIỂM TÊN ĐĂNG NHẬP TRƯỚC KHI GỬI — VÀ VÌ SAO ĐIỀU ĐÓ KHÔNG TRÁI VỚI `api.js`
 *
 * `api.js` nói frontend không tự dịch lỗi server, và luật ấy vẫn đúng: server vẫn là nơi
 * quyết định, mọi câu chữ từ server vẫn hiện nguyên văn, và bỏ đoạn kiểm dưới đây thì hệ
 * thống vẫn an toàn (bất biến #11 — chốt thật nằm ở `User.kiemTraHandle`).
 *
 * Đoạn này giải một vấn đề khác. Câu của server liệt kê thứ ĐƯỢC PHÉP; nó không nói ô của
 * bạn sai ở đâu. Người dùng Việt gõ "Hùng" — một cái tên hoàn toàn bình thường — rồi nhận
 * một danh sách quy tắc và phải tự đối chiếu để đoán ra rằng vấn đề là dấu thanh. Đó là câu
 * trả lời đúng cho câu hỏi sai.
 *
 * Nên ở đây trang nói ĐÚNG thứ hỏng trong ô ấy ("có dấu", "có khoảng trắng", "đây là ô tên
 * đăng nhập chứ không phải email") và đề nghị một tên hợp lệ dựng từ chính thứ họ vừa gõ.
 *
 * ★ MẪU REGEX ĐỌC TỪ DOM, KHÔNG VIẾT LẠI Ở ĐÂY
 *
 * Luật này đã sống ở hai nơi (`User.HANDLE_REGEX` và `CHECK` của V1). Thuộc tính `pattern`
 * trong `login.html` là bản thứ ba và `IdentityDomainTest` canh cả ba. Viết thêm một hằng
 * regex trong file này là bản thứ tư — bản duy nhất không ai canh.
 */

import { goi, luuPhien, LoiApi } from './api.js';
import { veThanh, bao, thamSo } from './khung.js';
import { DUONG } from './duong-dan.js';

veThanh();

const o = document.getElementById('thong-bao');
const oHandle = document.getElementById('handle');
const oLoiHandle = document.getElementById('loi-handle');
const mauHandle = new RegExp(oHandle.pattern);

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

/**
 * Bỏ dấu tiếng Việt để dựng một gợi ý hợp lệ.
 *
 * `NFD` tách dấu thanh thành ký tự tổ hợp (U+0300–U+036F) nên xoá được bằng một dải. Nhưng
 * <b>đ/Đ không tách</b> — nó là một chữ cái riêng, không phải d cộng dấu — nên phải thay tay.
 * Quên dòng ấy là gợi ý cho ra "-ung" từ "Đúng".
 */
function boDau(ten) {
    return ten.normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/đ/g, 'd')
        .replace(/Đ/g, 'D')
        .replace(/[^A-Za-z0-9_.-]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 32);
}

/** @returns {string|null} câu nói ĐÚNG chỗ hỏng, hoặc `null` nếu tên dùng được */
function loiHandle(h) {
    if (!h) return 'Chưa nhập tên đăng nhập.';
    if (mauHandle.test(h)) return null;

    // Thứ tự có chủ ý: hỏi cái cụ thể trước, câu chung chỉ dùng khi không nhận ra được gì.
    if (h.includes('@')) {
        return 'Đây là ô tên đăng nhập, không phải email — email có ô riêng ngay bên dưới.';
    }
    if (/\s/.test(h)) return 'Tên đăng nhập không được có khoảng trắng.';
    if (h.length < 3) return 'Ngắn quá — cần ít nhất 3 ký tự.';
    if (h.length > 32) return 'Dài quá — tối đa 32 ký tự.';
    if (/[^\x00-\x7F]/.test(h)) {
        return 'Tên đăng nhập không dùng được chữ có dấu. Tên thật có dấu thì điền ở ô '
            + '"Tên hiển thị" bên dưới.';
    }
    return 'Chỉ dùng được chữ không dấu, số, dấu chấm, gạch dưới và gạch ngang.';
}

/**
 * Hiện lỗi kèm một nút nhận gợi ý.
 *
 * Gợi ý chỉ hiện khi nó THẬT SỰ hợp lệ — đề nghị một cái tên rồi để server từ chối nó là tệ
 * hơn không đề nghị gì.
 */
function veLoiHandle(loi) {
    oLoiHandle.replaceChildren();
    oLoiHandle.hidden = !loi;
    if (!loi) {
        oHandle.removeAttribute('aria-invalid');
        return;
    }
    oHandle.setAttribute('aria-invalid', 'true');
    oLoiHandle.append(document.createTextNode(loi));

    const goiY = boDau(oHandle.value);
    if (goiY && goiY !== oHandle.value && mauHandle.test(goiY)) {
        oLoiHandle.append(document.createTextNode(' '));
        const nut = document.createElement('button');
        nut.type = 'button';
        nut.className = 'phu';
        nut.textContent = `Dùng "${goiY}"`;
        nut.addEventListener('click', () => {
            oHandle.value = goiY;
            veLoiHandle(null);
            oHandle.focus();
        });
        oLoiHandle.append(nut);
    }
}

// Báo khi rời ô, không phải khi đang gõ: gào lên "quá ngắn" ngay ở ký tự đầu tiên là mắng
// người ta vì chưa gõ xong.
oHandle.addEventListener('blur', () => veLoiHandle(loiHandle(oHandle.value.trim())));
oHandle.addEventListener('input', () => {
    if (!oLoiHandle.hidden && !loiHandle(oHandle.value.trim())) veLoiHandle(null);
});

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

    // Chặn ở đây để khỏi một vòng đi–về chỉ để nhận lại một câu kém rõ hơn câu ta đã có.
    // Server vẫn kiểm — đây là tiện ích, không phải chốt.
    const loi = loiHandle(form.handle.value.trim());
    if (loi) {
        veLoiHandle(loi);
        oHandle.focus();
        return;
    }

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
