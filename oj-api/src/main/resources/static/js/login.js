import { goi, luuPhien, LoiApi } from './api.js';
import { veThanh, bao, thamSo } from './khung.js';
import { DUONG } from './duong-dan.js';

veThanh();

const o = document.getElementById('thong-bao');
const oHandle = document.getElementById('handle');
const oLoiHandle = document.getElementById('loi-handle');
const mauHandle = new RegExp(oHandle.pattern);

const LY_DO = {
    'doi-mat-khau': ['Đã đổi mật khẩu. Đăng nhập lại bằng mật khẩu mới.', 'on'],
};
const viSao = LY_DO[thamSo('vi-sao')];
if (viSao) bao(o, viSao[0], viSao[1]);

function tiepTuc() {
    const t = thamSo('tiep');
    if (!t) return '/';
    try {
        const dich = new URL(t, location.origin);
        return dich.origin === location.origin ? dich.href : '/';
    } catch {
        return '/';
    }
}

function boDau(ten) {
    return ten.normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .replace(/đ/g, 'd')
        .replace(/Đ/g, 'D')
        .replace(/[^A-Za-z0-9_.-]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .slice(0, 32);
}

function loiHandle(h) {
    if (!h) return 'Chưa nhập tên đăng nhập.';
    if (mauHandle.test(h)) return null;

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

oHandle.addEventListener('blur', () => veLoiHandle(loiHandle(oHandle.value.trim())));
oHandle.addEventListener('input', () => {
    if (!oLoiHandle.hidden && !loiHandle(oHandle.value.trim())) veLoiHandle(null);
});

async function gui(form, duongDan, sauKhiXong, khiLoi = null) {
    const nut = form.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        const than = Object.fromEntries(new FormData(form).entries());
        const kq = await goi(duongDan, { method: 'POST', body: than });
        sauKhiXong(kq);
    } catch (e) {
        if (khiLoi && e instanceof LoiApi && khiLoi(e)) {
            nut.disabled = false;
            return;
        }
        bao(o, e instanceof LoiApi ? e.message : 'Không kết nối được máy chủ.', 'loi');
        nut.disabled = false;
    }
}

document.getElementById('form-dang-nhap').addEventListener('submit', (ev) => {
    ev.preventDefault();
    gui(ev.target, DUONG.auth.dangNhap, (phien) => {
        luuPhien(phien);
        location.href = tiepTuc();
    }, (e) => {
        if (e.code !== 'identity.can_totp') return false;
        const nhom = document.getElementById('nhom-hai-lop');
        nhom.hidden = false;
        const oMa = document.getElementById('ma-hai-lop');
        oMa.required = true;
        oMa.focus();
        bao(o, 'Tài khoản này bật xác thực hai lớp. Nhập mã từ ứng dụng.', '');
        return true;
    });
});

let veLaiCaptcha = () => {};

async function dungCaptcha() {
    let cauHinh;
    try {
        cauHinh = await goi(DUONG.auth.captcha);
    } catch (e) {
        return;
    }
    if (!cauHinh.enabled || !cauHinh.siteKey) return;

    await new Promise((xong, hong) => {
        const sc = document.createElement('script');
        sc.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
        sc.async = true;
        sc.onload = xong;
        sc.onerror = hong;
        document.head.appendChild(sc);
    }).catch(() => null);

    if (typeof window.turnstile !== 'function' && typeof window.turnstile !== 'object') return;

    const oToken = document.getElementById('captcha-token');
    const id = window.turnstile.render('#captcha-dang-ky', {
        sitekey: cauHinh.siteKey,
        callback: (token) => { oToken.value = token; },
        'expired-callback': () => { oToken.value = ''; },
        'error-callback': () => { oToken.value = ''; },
    });
    veLaiCaptcha = () => {
        oToken.value = '';
        window.turnstile.reset(id);
    };
}

dungCaptcha();

document.getElementById('form-dang-ky').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const form = ev.target;

    const loi = loiHandle(form.handle.value.trim());
    if (loi) {
        veLoiHandle(loi);
        oHandle.focus();
        return;
    }

    gui(form, DUONG.auth.dangKy, () => {
        const dn = document.getElementById('form-dang-nhap');
        dn.dinhDanh.value = form.handle.value;
        dn.password.value = form.password.value;
        bao(o, 'Đã tạo tài khoản. Đang đăng nhập…', 'on');
        dn.requestSubmit();
    }, () => {
        veLaiCaptcha();
        return false;
    });
});
