import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';
import { DUONG } from './duong-dan.js';

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
            bao(o, e instanceof LoiApi ? e.message : 'Không xoá được đề.', 'loi');
        }
    });

    return {
        dat(id, ma) {
            deHienTai = id;
            maHienTai = ma;
            khu.hidden = !id;
            form.reset();
        },
    };
}
