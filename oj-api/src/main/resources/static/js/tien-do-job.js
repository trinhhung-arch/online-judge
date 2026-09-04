/**
 * Theo dõi một job nền — Bước G10 và G11.
 *
 * ★ POLL, KHÔNG PHẢI SSE — VÀ ĐÓ LÀ KHÁC BIỆT VỚI BẢNG XẾP HẠNG
 *
 * Bảng xếp hạng có SSE vì hàng trăm người xem cùng một thứ cùng lúc; một job nạp testdata
 * có đúng một người xem, người vừa bấm nút. Mở một luồng cho mỗi lần nạp là trả giá hạ tầng
 * của bài toán kia để giải bài toán này. Server cũng không có endpoint luồng cho job.
 *
 * ★ NHỊP GIÃN DẦN
 *
 * Một job nạp testdata chạy vài giây; một job chấm lại cả đề chạy vài phút. Poll cố định
 * 1 giây thì cái sau tạo hàng trăm request cho một thanh tiến độ nhích rất chậm. Nhịp bắt
 * đầu nhanh — để việc ngắn có cảm giác tức thì — rồi giãn tới trần.
 *
 * ★ JOB KHÔNG BAO GIỜ "MẤT"
 *
 * Rời trang giữa chừng KHÔNG huỷ job: nó sống trong `jobs` ở server. Nên khi dừng theo dõi,
 * câu chữ phải nói đúng điều đó — người dùng bỏ trang vì tưởng đã hỏng rồi nạp lại lần nữa
 * là cách sinh ra hai bản testdata cho cùng một đề.
 */

import { goi, LoiApi } from './api.js';
import { chu } from './khung.js';
import { DUONG } from './duong-dan.js';

const NHIP_DAU_MS = 700;
const NHIP_TRAN_MS = 5000;
const HE_SO_GIAN = 1.4;

/** Trạng thái kết thúc — xem JobStatus của server. Tới đây thì ngừng hỏi. */
const DA_XONG = new Set(['DONE', 'FAILED', 'CANCELLED']);

const NHAN = {
    PENDING: 'Đang xếp hàng',
    RUNNING: 'Đang chạy',
    PAUSED: 'Tạm nghỉ — sẽ tự chạy tiếp',
    DONE: 'Xong',
    FAILED: 'Thất bại',
    CANCELLED: 'Đã huỷ',
};

/**
 * @param {number} jobId
 * @param {object} c
 *   `vao`    phần tử để vẽ tiến độ vào
 *   `khiXong` (job) => void, gọi đúng một lần khi job tới trạng thái kết thúc
 * @returns {{dung: () => void}} gọi `dung()` khi rời trang
 */
export function theoDoi(jobId, { vao, khiXong }) {
    let hen = null;
    let nhip = NHIP_DAU_MS;
    let song = true;

    function ve(job) {
        const khoi = chu('div', null, 'tien-do');

        khoi.append(chu('p', `Việc #${job.id} · ${NHAN[job.status] || job.status}`));

        // `totalItems` là null cho tới khi handler biết tổng — thanh tiến độ chỉ có nghĩa
        // sau lúc ấy. Trước đó hiện số đã xong, vì "3 mục" trung thực hơn một thanh giả.
        if (job.totalItems) {
            const thanh = document.createElement('progress');
            thanh.max = job.totalItems;
            thanh.value = job.doneItems;
            const nhan = `${job.doneItems}/${job.totalItems}`;
            thanh.setAttribute('aria-label', `Tiến độ việc ${job.id}: ${nhan}`);
            khoi.append(thanh);
            // ★ Số nằm cạnh thanh: `<progress>` không được trình đọc màn hình đọc nhất quán,
            // và người nhìn cũng cần con số chứ không chỉ một vệt màu.
            khoi.append(chu('span', ` ${nhan}`, 'goi-y'));
        } else if (job.doneItems) {
            khoi.append(chu('p', `Đã xong ${job.doneItems} mục.`, 'goi-y'));
        }

        if (job.errorMessage) {
            khoi.append(chu('p', job.errorMessage, 'loi'));
        }

        const cacSuKien = job.suKien || [];
        if (cacSuKien.length) {
            const ds = chu('ul', null, 'su-kien');
            // Mới nhất trước: dòng người ta cần là dòng vừa xảy ra.
            for (const sk of [...cacSuKien].reverse().slice(0, 5)) {
                ds.append(chu('li', `${sk.level} · ${sk.message}`));
            }
            khoi.append(ds);
        }

        vao.replaceChildren(khoi);
    }

    async function hoi() {
        if (!song) return;
        try {
            const job = await goi(DUONG.viec.theoId(jobId));
            ve(job);

            if (DA_XONG.has(job.status)) {
                song = false;
                khiXong?.(job);
                return;
            }
        } catch (e) {
            vao.replaceChildren(chu('p',
                e instanceof LoiApi ? e.message : 'Mất liên lạc khi theo dõi tiến độ. '
                    + 'Việc vẫn đang chạy ở máy chủ — tải lại trang để xem tiếp.', 'loi'));
            song = false;
            return;
        }
        nhip = Math.min(Math.round(nhip * HE_SO_GIAN), NHIP_TRAN_MS);
        hen = setTimeout(hoi, nhip);
    }

    hoi();

    return {
        dung() {
            song = false;
            if (hen) clearTimeout(hen);
        },
    };
}
