import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';

const o = document.getElementById('thong-bao');
const oTrangThai = document.getElementById('xac-minh-trang-thai');
const khoiChua = document.getElementById('xac-minh-chua');
const nutGui = document.getElementById('nut-gui-ma');
const formXacMinh = document.getElementById('form-xac-minh');

function veTrangThai(daXacMinh) {
    khoiChua.hidden = daXacMinh;
    oTrangThai.textContent = daXacMinh
        ? 'Email của bạn đã được xác minh.'
        : 'Email của bạn chưa được xác minh.';
}

async function tai() {
    try {
        veTrangThai((await goi(DUONG.toi.hoSo)).emailVerified);
    } catch (e) {
        oTrangThai.textContent = 'Không đọc được trạng thái xác minh.';
    }
}

nutGui.addEventListener('click', async () => {
    nutGui.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.toi.xacMinhEmail, { method: 'POST' });
        formXacMinh.hidden = false;
        document.getElementById('xac-minh-ma').focus();
        bao(o, 'Đã gửi mã. Kiểm tra hộp thư — và cả thư mục spam.', 'on');
    } catch (e) {
        bao(o, e instanceof LoiApi ? e.message : 'Không gửi được mã.', 'loi');
    } finally {
        nutGui.disabled = false;
    }
});

formXacMinh.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const nut = formXacMinh.querySelector('button[type=submit]');
    nut.disabled = true;
    bao(o, '');
    try {
        await goi(DUONG.toi.xacMinhEmailXacNhan, {
            method: 'POST',
            body: { ma: formXacMinh.ma.value },
        });
        formXacMinh.hidden = true;
        formXacMinh.reset();
        veTrangThai(true);
        bao(o, 'Đã xác minh email.', 'on');
    } catch (e) {
        formXacMinh.reset();
        bao(o, e instanceof LoiApi ? e.message : 'Không xác minh được.', 'loi');
    } finally {
        nut.disabled = false;
    }
});

tai();
