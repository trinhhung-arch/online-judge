import { goi, LoiApi } from './api.js';
import { chu } from './khung.js';
import { DUONG } from './duong-dan.js';

const NHIP_DAU_MS = 700;
const NHIP_TRAN_MS = 5000;
const HE_SO_GIAN = 1.4;

const DA_XONG = new Set(['DONE', 'FAILED', 'CANCELLED']);

const NHAN = {
    PENDING: 'Đang xếp hàng',
    RUNNING: 'Đang chạy',
    PAUSED: 'Tạm nghỉ — sẽ tự chạy tiếp',
    DONE: 'Xong',
    FAILED: 'Thất bại',
    CANCELLED: 'Đã huỷ',
};

export function theoDoi(jobId, { vao, khiXong }) {
    let hen = null;
    let nhip = NHIP_DAU_MS;
    let song = true;

    function ve(job) {
        const khoi = chu('div', null, 'tien-do');

        khoi.append(chu('p', `Việc #${job.id} · ${NHAN[job.status] || job.status}`));

        if (job.totalItems) {
            const thanh = document.createElement('progress');
            thanh.max = job.totalItems;
            thanh.value = job.doneItems;
            const nhan = `${job.doneItems}/${job.totalItems}`;
            thanh.setAttribute('aria-label', `Tiến độ việc ${job.id}: ${nhan}`);
            khoi.append(thanh);
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
