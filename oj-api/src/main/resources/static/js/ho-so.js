import { goi, LoiApi, xoaPhien } from './api.js';
import { chu, bao } from './khung.js';
import { khoiDong, gio } from './trang.js';
import { DUONG } from './duong-dan.js';

const o = khoiDong({ doiDangNhap: true });
if (o) {
    const formHoSo = document.getElementById('form-ho-so');
    const formMatKhau = document.getElementById('form-mat-khau');
    const chonNgonNgu = document.getElementById('ngon-ngu');
    const chiSoDoc = document.getElementById('chi-so-doc');

    const VAI_TRO = { USER: 'Người dùng', SETTER: 'Người ra đề', ADMIN: 'Quản trị viên' };

    function dongChiSoDoc(nhan, giaTri) {
        chiSoDoc.append(chu('dt', nhan), chu('dd', giaTri));
    }

    async function taiHoSo() {
        const hs = await goi(DUONG.toi.hoSo);

        chiSoDoc.replaceChildren();
        dongChiSoDoc('Tên đăng nhập', hs.handle);
        dongChiSoDoc('Email', hs.email);
        dongChiSoDoc('Vai trò', VAI_TRO[hs.role] || hs.role);
        dongChiSoDoc('Tham gia', gio(hs.createdAt));

        formHoSo.displayName.value = hs.displayName || '';
        chonNgonNgu.value = hs.preferredLanguageId ?? '';
        return hs;
    }

    async function taiNgonNgu(idDangChon) {
        try {
            for (const n of await goi(DUONG.ngonNgu)) {
                const opt = chu('option', n.displayName);
                opt.value = n.id;
                chonNgonNgu.append(opt);
            }
            chonNgonNgu.value = idDangChon ?? '';
        } catch {
            chonNgonNgu.disabled = true;
            chonNgonNgu.insertAdjacentElement('afterend',
                chu('p', 'Không tải được danh sách ngôn ngữ.', 'goi-y'));
        }
    }

    formHoSo.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const nut = formHoSo.querySelector('button[type=submit]');
        nut.disabled = true;
        bao(o, '');
        try {
            const chon = chonNgonNgu.value;
            await goi(DUONG.toi.hoSo, {
                method: 'PATCH',
                body: {
                    displayName: formHoSo.displayName.value.trim(),
                    preferredLanguageId: chon === '' ? null : Number(chon),
                },
            });
            bao(o, 'Đã lưu hồ sơ.', 'on');
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không lưu được hồ sơ.', 'loi');
        } finally {
            nut.disabled = false;
        }
    });

    formMatKhau.addEventListener('submit', async (ev) => {
        ev.preventDefault();
        const nut = formMatKhau.querySelector('button[type=submit]');

        if (formMatKhau.matKhauMoi.value !== formMatKhau.matKhauLai.value) {
            bao(o, 'Hai ô mật khẩu mới không giống nhau.', 'loi');
            formMatKhau.matKhauLai.focus();
            return;
        }

        nut.disabled = true;
        bao(o, '');
        try {
            await goi(DUONG.toi.matKhau, {
                method: 'POST',
                body: {
                    matKhauCu: formMatKhau.matKhauCu.value,
                    matKhauMoi: formMatKhau.matKhauMoi.value,
                },
            });
            xoaPhien();
            location.href = '/login.html?vi-sao=doi-mat-khau';
        } catch (e) {
            bao(o, e instanceof LoiApi ? e.message : 'Không đổi được mật khẩu.', 'loi');
            nut.disabled = false;
        }
    });

    taiHoSo()
        .then((hs) => taiNgonNgu(hs.preferredLanguageId))
        .catch((e) => bao(o, e instanceof LoiApi ? e.message : 'Không tải được hồ sơ.', 'loi'));
}
