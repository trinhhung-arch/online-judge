import { goi, LoiApi } from './api.js';
import { bao } from './khung.js';

export function taoPhanTrang({ ds, boLoc, veDong, vao, nutThem, o, khiTrong, loiChung }) {
    let cursor = null;
    let dangTai = false;

    async function tai(noiTiep = false) {
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
        lamMoi: () => tai(false),
        khoaSize: ds.khoaSize,
    };
}
