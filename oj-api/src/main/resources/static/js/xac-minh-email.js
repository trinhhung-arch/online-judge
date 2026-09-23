/**
 * Xác minh email trên trang hồ sơ — FR-AUTH-09 (V13).
 *
 * ★ MỨC MỀM, VÀ TRANG PHẢI NÓI RA ĐIỀU ĐÓ
 *
 * Nhãn `emailVerified` không chặn gì cả: chưa xác minh thì vẫn đăng nhập, vẫn nộp bài, vẫn
 * dự thi. Nhưng người dùng của một hệ thống chấm bài đã quen với việc mọi cảnh báo đều chặn
 * họ làm gì đó — nên nếu khối này trông như một cảnh báo, họ sẽ tưởng bài nộp của mình
 * không được chấm.
 *
 * Vì thế: không màu đỏ, không dấu chấm than, và câu đầu tiên trong HTML nói thẳng "không bắt
 * buộc". Một lời hứa kiến trúc chỉ có giá trị khi nó đọc được trên màn hình.
 *
 * ★ Ô NHẬP MÃ CHỈ HIỆN SAU KHI ĐÃ GỬI
 *
 * Mỗi mã chỉ chịu được năm lượt gõ sai rồi chết. Bày sẵn một ô cho người chưa nhận mã nào là
 * mời họ gõ bừa, và năm lần bừa là mã thật — lúc nó tới — đã hỏng trước khi được dùng.
 *
 * ★ TÁCH KHỎI `ho-so.js` cùng lý do với `hai-lop.js`: file kia đã 127 dòng, và ranh giới
 * "phần nào của trang nói chuyện với endpoint nào" nhìn thấy được bằng một lệnh `grep`.
 */

import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';

// KHÔNG gọi khoiDong(): `ho-so.js` nạp trước và đã gọi rồi. Gọi lần hai sẽ vẽ thanh điều
// hướng hai lần và gắn thêm một listener unhandledrejection nữa.

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
        // Hồ sơ không tải được thì `ho-so.js` đã nói rồi — nó gọi cùng một endpoint. Lặp
        // lại câu ấy ở đây là hai thông báo cho một sự cố.
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
        // Câu chữ đến thẳng từ server: "máy chủ chưa bật xác minh email", "vừa gửi rồi, thử
        // lại sau N giây", "không gửi được thư lúc này". Ba tình huống khác hẳn nhau, và chỉ
        // server biết đang là cái nào. Dịch lại ở đây là tạo một bản dịch sẽ lạc hậu trước.
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
        // Xoá ô mã sau mỗi lần hỏng: mã đã tiêu một lượt trong năm lượt, và để nguyên giá
        // trị cũ trên màn hình mời người ta bấm lại đúng cái mã vừa bị từ chối.
        formXacMinh.reset();
        bao(o, e instanceof LoiApi ? e.message : 'Không xác minh được.', 'loi');
    } finally {
        nut.disabled = false;
    }
});

tai();
