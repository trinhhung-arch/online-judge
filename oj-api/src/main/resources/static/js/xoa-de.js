/**
 * Khối "Xoá đề" của trang soạn đề.
 *
 * ★ VÌ SAO NÓ ĐỨNG RIÊNG, CẢ TRONG TRANG LẪN TRONG MÃ NGUỒN
 *
 * Lưu và Gỡ xuống đều hoàn tác được. Xoá thì không. Ba thao tác ấy nằm cùng một hàng nút là
 * một lời mời bấm nhầm, nên trong HTML nó là một khối riêng ở cuối trang — và ở đây nó là
 * một module riêng, vì cùng một lý do: ai đọc `ra-de.js` không cần đọc nhầm vào nó.
 *
 * ★ GÕ LẠI MÃ ĐỀ, KHÔNG PHẢI confirm()
 *
 * Một hộp thoại chỉ có OK/Cancel thì OK là phản xạ — người ta bấm xong mới đọc. Gõ lại đúng
 * mã của thứ mình sắp xoá thì không gõ nhầm được. Cùng khuôn với thao tác ẩn danh hoá tài
 * khoản ở `quan-tri.html`, và cùng lý do.
 *
 * Chốt thật vẫn ở máy chủ: `AuthorProblemUseCase.xoa` từ chối đề đã có bài nộp hoặc còn
 * thuộc kỳ thi. Ô gõ lại chỉ chặn tay trượt, không chặn được ai cố tình.
 *
 * Tách khỏi `ra-de.js` ở V10 vì file ấy vượt trần 300 dòng (CLAUDE.md mục 7).
 */

import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';

/**
 * @param o        ô thông báo của trang
 * @param khiXong  gọi sau khi xoá thành công — trang tự quyết định dọn form thế nào
 * @returns {{dat: (function(?number, ?string): void)}} `dat(id, ma)` mỗi lần đổi đề đang mở;
 *          `id` rỗng nghĩa là đang soạn đề mới, và khi ấy khối này biến mất
 */
export function ganXoaDe({ o, khiXong }) {
    const khu = document.getElementById('khu-xoa');
    const form = document.getElementById('form-xoa');

    let deHienTai = null;
    let maHienTai = null;

    form.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const goVao = form.elements.code.value.trim();
        if (goVao !== maHienTai) {
            bao(o, `Mã đề không khớp. Gõ đúng “${maHienTai}” để xác nhận xoá.`, 'loi');
            return;
        }
        bao(o, '');
        try {
            await goi(DUONG.de.xoa(deHienTai), { method: 'DELETE' });
            bao(o, `Đã xoá đề “${goVao}”.`, 'on');
            khiXong();
        } catch (e) {
            // Câu của server nói rõ nên làm gì thay thế ("Dùng Gỡ xuống…"), nên hiện nguyên văn.
            bao(o, e instanceof LoiApi ? e.message : 'Không xoá được đề.', 'loi');
        }
    });

    return {
        dat(id, ma) {
            deHienTai = id;
            maHienTai = ma;
            // Không có gì để xoá khi đang soạn một đề chưa tồn tại.
            khu.hidden = !id;
            form.reset();
        },
    };
}
