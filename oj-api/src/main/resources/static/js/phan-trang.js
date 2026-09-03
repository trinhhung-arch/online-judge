/**
 * Phân trang cursor — Bước G1. Dùng chung cho mọi danh sách.
 *
 * ★ CURSOR, KHÔNG PHẢI OFFSET — và đó là quyết định của server
 *
 * `oj-api/CLAUDE.md` mục 3: `WHERE id < :cursor ORDER BY id DESC LIMIT :size`. Không có
 * tổng số bản ghi, nên không có "trang 5 / 120" và cũng sẽ không bao giờ có: `COUNT(*)`
 * trên `submissions` là một lần quét bảng có hàng triệu dòng.
 *
 * Hệ quả cho giao diện: **nút "Tải thêm", không phải dãy số trang.** Đó không phải lười —
 * dãy số trang cần một tổng mà server cố ý không tính.
 *
 * ★ TÊN THAM SỐ KÍCH THƯỚC ĐI KÈM ĐƯỜNG DẪN, KHÔNG ĐOÁN
 *
 * `/submissions` nhận `limit`, `/problems` nhận `size`. Sai tên thì Spring bỏ qua trong im
 * lặng và trả về mặc định — client tưởng phân trang chạy. Nên `taoPhanTrang` nhận trọn một
 * mô tả từ `js/duong-dan.js` chứ không nhận riêng một chuỗi URL.
 */

import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';

/**
 * @param {object} c
 *   `ds`      mô tả endpoint từ `DS` của duong-dan.js — `{ url, khoaSize }`
 *   `boLoc`   () => URLSearchParams — bộ lọc hiện tại, KHÔNG gồm cursor
 *   `veDong`  (item) => Node — dựng một dòng
 *   `vao`     phần tử chứa các dòng (thường là `<tbody>`)
 *   `nutThem` nút "Tải thêm"
 *   `o`       vùng #thong-bao
 *   `khiTrong` câu hiển thị khi không có dòng nào
 *   `loiChung` câu hiển thị khi lỗi không phải LoiApi
 */
export function taoPhanTrang({ ds, boLoc, veDong, vao, nutThem, o, khiTrong, loiChung }) {
    let cursor = null;
    let dangTai = false;

    async function tai(noiTiep = false) {
        // Bấm "Tải thêm" hai lần nhanh tay sẽ gửi hai request cùng cursor và chèn trùng dòng.
        if (dangTai) return;
        dangTai = true;
        nutThem.disabled = true;

        if (!noiTiep) {
            cursor = null;
            vao.replaceChildren();
        }
        bao(o, '');

        try {
            const q = boLoc();
            if (cursor) q.set('cursor', cursor);
            const trang = await goi(`${ds.url}?${q}`);

            for (const item of trang.items) vao.append(veDong(item));

            // Server trả chuỗi rỗng thay cho null ở vài endpoint — cả hai đều nghĩa là hết.
            cursor = trang.nextCursor || null;
            nutThem.hidden = !cursor;

            if (!vao.childElementCount) bao(o, khiTrong);
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : loiChung, 'loi');
            nutThem.hidden = true;
        } finally {
            dangTai = false;
            nutThem.disabled = false;
        }
    }

    nutThem.addEventListener('click', () => tai(true));

    return {
        /** Tải lại từ đầu — gọi khi bộ lọc đổi. */
        lamMoi: () => tai(false),
        /** Kích thước trang mà endpoint này dùng, để nơi gọi đặt vào bộ lọc nếu muốn. */
        khoaSize: ds.khoaSize,
    };
}
