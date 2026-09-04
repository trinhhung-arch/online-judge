/**
 * Danh sách "mở nhanh" của trang soạn đề — BỘ NHỚ CỦA TRÌNH DUYỆT NÀY.
 *
 * ★ Vì sao nó phải tồn tại, và vì sao nó không đủ
 *
 * Máy chủ chỉ liệt kê đề ĐÃ XUẤT BẢN (`GET /api/v1/problems` lọc PUBLISHED), nên không có
 * cách nào hỏi "những bản nháp của tôi đâu". Thứ duy nhất mở lại được một bản nháp là
 * `problemId` trả về đúng một lần, lúc tạo. Danh sách này giữ hộ con số ấy.
 *
 * Nhưng nó nằm ở `localStorage`, nên nó mất khi đổi máy, đổi trình duyệt, hoặc xoá dữ liệu
 * duyệt web. Mất lối tắt, không mất đề — và đó là lý do trang vẫn có ô nhập số bằng tay.
 * Đừng biến danh sách này thành đường đi DUY NHẤT tới một bản nháp.
 *
 * Tách khỏi `ra-de.js` ở V10 vì file ấy vượt trần 300 dòng (CLAUDE.md mục 7). Đường cắt
 * không tuỳ tiện: đây là thứ duy nhất trong trang không nói chuyện với máy chủ.
 */

import { chu } from './khung.js';

const KHOA = 'oj.ra-de.gan-day';

/** Giữ 12 đề gần nhất — đủ cho một đợt soạn đề, chưa đủ để thành một danh sách phải cuộn. */
const TOI_DA = 12;

function doc() {
    try {
        const ds = JSON.parse(localStorage.getItem(KHOA) || '[]');
        return Array.isArray(ds) ? ds : [];
    } catch {
        // JSON hỏng, hoặc trình duyệt chặn localStorage. Cả hai đều là "không có lối tắt".
        return [];
    }
}

/** Đưa một đề lên đầu danh sách. Gọi sau mỗi lần tạo, lưu, hoặc mở. */
export function nhoLai(problemId, code) {
    const ds = doc().filter((x) => x.id !== problemId);
    ds.unshift({ id: problemId, code });
    try {
        localStorage.setItem(KHOA, JSON.stringify(ds.slice(0, TOI_DA)));
    } catch {
        // Chế độ riêng tư hoặc hết dung lượng. Im lặng là đúng: một dòng đỏ ở đây làm người
        // ta tưởng đề chưa lưu được, trong khi đề đã nằm yên trên máy chủ.
    }
}

/**
 * Vẽ danh sách vào `vao`.
 *
 * @param moDe hàm nhận `problemId` — trang tự quyết định "mở" nghĩa là gì
 */
export function veGanDay(vao, moDe) {
    const ds = doc();
    vao.replaceChildren();
    if (!ds.length) return;

    vao.append(chu('p', 'Mở nhanh (nhớ trên máy này):', 'goi-y'));
    const hang = chu('p', null, 'hang');
    for (const x of ds) {
        const nut = chu('button', `#${x.id} · ${x.code || 'chưa đặt mã'}`, 'phu');
        nut.type = 'button';
        nut.addEventListener('click', () => moDe(x.id));
        hang.append(nut);
    }
    vao.append(hang);
}
