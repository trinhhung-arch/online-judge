/**
 * Khởi động một trang — Bước G1.
 *
 * Bốn dòng lặp lại ở đầu mọi trang gom vào một chỗ: dựng thanh điều hướng, chốt đăng nhập,
 * tìm vùng thông báo, và bắt lỗi bất đồng bộ không ai bắt.
 *
 * ★ DÒNG ĐÁNG GIÁ NHẤT LÀ `unhandledrejection`
 *
 * Mọi trang hiện có đều gọi `tai()` ở cuối file mà không `.catch()`. Một lỗi ném ra từ đó —
 * mạng đứt giữa chừng, JSON hỏng, một `null` không lường trước — rơi vào console và trang
 * đứng im, KHÔNG một chữ nào cho người dùng. Họ nhìn một bảng trống và không biết là "chưa
 * có dữ liệu" hay "vừa hỏng".
 *
 * Với một hệ thống mà người dùng đang chờ verdict, một trang im lặng còn tệ hơn một trang
 * báo lỗi: họ sẽ F5 mãi thay vì biết rằng cần thử lại sau.
 */

import { veThanh, bao, canDangNhap } from './khung.js';

/**
 * @param {object} tuyChon
 *   `doiDangNhap` — trang chỉ có nghĩa khi đã đăng nhập; chưa thì chuyển sang /login.html
 *                   và trả về `null` để phần còn lại của module dừng lại.
 * @returns {HTMLElement|null} vùng `#thong-bao`, hoặc `null` khi đang chuyển hướng
 */
export function khoiDong({ doiDangNhap = false } = {}) {
    veThanh();

    if (doiDangNhap && !canDangNhap()) {
        return null;
    }

    const o = document.getElementById('thong-bao');

    window.addEventListener('unhandledrejection', (ev) => {
        // Không nuốt: vẫn để nó vào console cho người gỡ lỗi. Chỉ thêm một câu cho người dùng.
        if (o) {
            bao(o, 'Trang gặp lỗi không mong muốn. Tải lại trang, hoặc thử lại sau ít phút.', 'loi');
        }
    });

    return o;
}

/**
 * Định dạng một mốc thời gian ISO của server thành giờ địa phương.
 *
 * Server luôn trả UTC (`Instant`). Hiện nguyên chuỗi ISO là bắt người dùng tự trừ múi giờ,
 * và trong một kỳ thi thì "nộp lúc mấy giờ" là câu hỏi có hậu quả.
 */
export function gio(isoUtc) {
    if (!isoUtc) return '—';
    const d = new Date(isoUtc);
    return Number.isNaN(d.getTime())
        ? '—'
        : d.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'medium' });
}
