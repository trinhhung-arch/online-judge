import { goi, LoiApi } from './api.js';
import { chu, bao } from './khung.js';
import { DUONG } from './duong-dan.js';

const o = document.getElementById('thong-bao');
const oTrangThai = document.getElementById('hai-lop-trang-thai');

const khoi = {
    tat:      document.getElementById('hai-lop-tat'),
    dangKy:   document.getElementById('hai-lop-dang-ky'),
    maDuPhong: document.getElementById('hai-lop-ma-du-phong'),
    bat:      document.getElementById('hai-lop-bat'),
};

function hien(ten, nhan) {
    Object.entries(khoi).forEach(([k, el]) => { el.hidden = k !== ten; });
    oTrangThai.textContent = nhan;
}

function veQr(uri) {
    const img = document.getElementById('hai-lop-qr');
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
